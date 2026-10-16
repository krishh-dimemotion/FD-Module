package com.banklab.fdservice.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.banklab.fdservice.client.CustomerDto;
import com.banklab.fdservice.client.CustomerServiceClient;
import com.banklab.fdservice.client.ProductDetails;
import com.banklab.fdservice.client.ProductPricingClient;
import com.banklab.fdservice.client.ProductPricingClientException;
import com.banklab.fdservice.client.RateDetails;
import com.banklab.fdservice.client.UpstreamServiceException.Reason;
import com.banklab.fdservice.dto.AccountStatus;
import com.banklab.fdservice.dto.AssignRoleRequest;
import com.banklab.fdservice.dto.CreateFdAccountRequest;
import com.banklab.fdservice.dto.InterestFrequency;
import com.banklab.fdservice.dto.InterestType;
import com.banklab.fdservice.dto.RoleType;
import com.banklab.fdservice.entity.FdAccount;
import com.banklab.fdservice.entity.FdAccountRole;
import com.banklab.fdservice.repository.FdAccountRepository;
import com.banklab.fdservice.repository.FdAccountRoleRepository;

import lombok.RequiredArgsConstructor;

/**
 * Orchestrates FD account creation (openapi createFdAccount): resolves
 * customer/product/rate/category against Groups 1 and 2, mints the account
 * number, computes maturity date and the quoted maturity amount, and persists the
 * account, its initial role(s) and the initial DEPOSIT posting in one transaction.
 */
@Service
@RequiredArgsConstructor
public class FdAccountService {

    private final ProductPricingClient productPricingClient;
    private final CustomerServiceClient customerServiceClient;
    private final AccountNumberGeneratorService accountNumberGeneratorService;
    private final FdAccountRepository accountRepository;
    private final FdAccountRoleRepository roleRepository;
    private final BusinessClockService businessClockService;
    private final InterestCalculator interestCalculator;
    private final FdLedgerService ledgerService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.banklab.fdservice.tax.TaxService taxes;

    @Value("${fd.account-number.default-branch-cd}")
    private String defaultBranchCd;

    @Value("${fd.interest.default-interest-type}")
    private InterestType defaultInterestType;

    @Value("${fd.interest.day-count-convention}")
    private String defaultDayCountConvention;

    @Transactional
    public com.banklab.fdservice.dto.FdAccount createAccount(CreateFdAccountRequest request) {
        CustomerDto customer = resolveCustomer(request.custId());
        ProductDetails product = resolveProduct(request.productCode());
        validatePrincipalPrecision(request.principal(), firstNonBlank(request.currencyCode(), product.currency()));
        RateDetails rate = resolveRate(request.rateId());
        resolveCategory(request.categoryCd());
        validateRoleCardinality(request.initialRoles());

        String accountNumber = accountNumberGeneratorService.generateAccountNumber(defaultBranchCd);
        // Business date, not the wall clock (ERD P4) — keeps booking consistent with batch time travel.
        LocalDate openDt = businessClockService.currentBusinessDate();
        LocalDate matDt = openDt.plusMonths(request.tenureMonths());

        FdAccount account = buildAccount(request, accountNumber, customer, product, rate, openDt, matDt);
        account.setMatAmt(quoteMaturityAmount(account));
        account.stampAudit(FdLedgerService.API_USER, "C");

        // saveAndFlush: FD_TRANSACTIONS.FDA_ID is an enforced FK, so the account row must
        // be in the database before the DEPOSIT row's identity insert runs.
        FdAccount savedAccount = accountRepository.saveAndFlush(account);
        roleRepository.saveAll(buildRoles(savedAccount.getFdaId(), request.initialRoles(), openDt));
        ledgerService.postDeposit(savedAccount, openDt, UUID.randomUUID(), FdLedgerService.API_USER);

        return FdAccountMapper.toResponse(savedAccount);
    }

    private CustomerDto resolveCustomer(String custId) {
        return customerServiceClient.getCustomer(custId);
    }

    private ProductDetails resolveProduct(String productCode) {
        return productPricingClient.getProduct(productCode);
    }

    private RateDetails resolveRate(String rateId) {
        return productPricingClient.getRate(rateId)
                .orElseThrow(() -> new ProductPricingClientException(
                        "Rate not found for rateId=" + rateId, Reason.NOT_FOUND));
    }

    private void resolveCategory(String categoryCd) {
        if (!StringUtils.hasText(categoryCd)) {
            return;
        }
        productPricingClient.getCategory(categoryCd)
                .orElseThrow(() -> new CategoryMappingUnresolvedException(categoryCd));
    }

    /**
     * The principal must fit the currency's minor units and FDA_PRINCIPAL_AMT's
     * DECIMAL(20,4) — otherwise MySQL silently rounds the stored amount while the
     * maturity quote was computed on the unrounded one.
     */
    private static void validatePrincipalPrecision(BigDecimal principal, String currency) {
        int allowed = Math.min(CurrencyDecimals.forCurrency(currency), PRINCIPAL_COLUMN_SCALE);
        if (principal.precision() - principal.scale() > 16) throw new InvalidPrincipalException("principal exceeds 16 integer digits");
        if (principal.stripTrailingZeros().scale() > allowed) {
            throw new InvalidPrincipalException("principal " + principal.toPlainString() + " has more than "
                    + allowed + " decimal place(s) allowed for " + currency);
        }
    }

    private static final int PRINCIPAL_COLUMN_SCALE = 4;

    private void validateRoleCardinality(List<AssignRoleRequest> initialRoles) {
        requireAtMostOne(initialRoles, RoleType.GUARANTOR);
        requireAtMostOne(initialRoles, RoleType.GUARDIAN);
    }

    private void requireAtMostOne(List<AssignRoleRequest> roles, RoleType roleType) {
        long count = roles.stream().filter(role -> role.roleType() == roleType).count();
        if (count > 1) {
            throw new RoleCardinalityViolationException(roleType);
        }
    }

    /**
     * FDA_MAT_AMT quoted at booking with the same calculator the batch uses, so the
     * maturity payout matches the quote. A rate row without an effective rate fails
     * here (InterestTerms rejects a null rate → 422) before anything is saved.
     */
    private BigDecimal quoteMaturityAmount(FdAccount account) {
        InterestTerms terms = InterestTerms.fromAccount(account, defaultInterestType);
        return interestCalculator.calculate(account.getPrincipalAmt(), terms, account.getValueDt(), account.getMatDt(),
                taxes == null ? (gross,date)->BigDecimal.ZERO : taxes.session(account,account.getValueDt(),account.getMatDt(),false)::assess)
                .maturityValue();
    }

    private FdAccount buildAccount(CreateFdAccountRequest request, String accountNumber, CustomerDto customer,
            ProductDetails product, RateDetails rate, LocalDate openDt, LocalDate matDt) {
        String currency = CurrencyDecimals.normalize(firstNonBlank(request.currencyCode(), product.currency()));
        return FdAccount.builder()
                .taxEnabled(taxes != null && taxes.enabledForNew())
                .fdaId(UUID.randomUUID())
                .acctNum(accountNumber)
                .custId(request.custId())
                .prdCode(request.productCode())
                .rateId(request.rateId())
                .categoryCd(request.categoryCd())
                .ccyCd(currency)
                .ccyDecimals(CurrencyDecimals.forCurrency(currency))
                .principalAmt(request.principal())
                .principalBal(request.principal())
                .accruedIntAmt(BigDecimal.ZERO)
                .intRt(rate.effectiveRate())
                .intTyp(resolveInterestType(request, product).name())
                .compoundFreq(frequency(firstNonBlank(request.compoundingFreq(), product.defaultCompoundingFrequency())))
                .payoutFreq(frequency(firstNonBlank(request.payoutFreq(), product.defaultPayoutFrequency())))
                .dayCountConv(DayCountConvention.fromCode(defaultDayCountConvention).code())
                .tenureMonths(request.tenureMonths())
                .openDt(openDt)
                .valueDt(openDt)
                .matDt(matDt)
                .matInstruction(request.maturityInstruction() != null
                        ? request.maturityInstruction().name()
                        : product.defaultMaturityInstruction())
                .sts(AccountStatus.ACTIVE.name())
                .custNameSnap(customer.fullName())
                .prdNameSnap(product.productName())
                .efctvDt(openDt)
                .build();
    }

    /**
     * FDA_INT_TYP precedence: the request, then the product's default from Group 2,
     * then fd.interest.default-interest-type. An unknown product value is a 422.
     */
    private InterestType resolveInterestType(CreateFdAccountRequest request, ProductDetails product) {
        if (request.interestType() != null) {
            return request.interestType();
        }
        return InterestTerms.parseInterestType(product.defaultInterestType(), defaultInterestType);
    }

    /** Validates a frequency at booking so a typo is a 422 now, not a skipped account in tonight's batch. */
    private static String frequency(String value) {
        InterestFrequency parsed = InterestFrequency.parse(value);
        return parsed == null ? null : parsed.name();
    }

    private List<FdAccountRole> buildRoles(UUID fdaId, List<AssignRoleRequest> initialRoles, LocalDate efctvDt) {
        // Collectors.toList(), not Stream.toList() — Lombok's @SuperBuilder.builder()
        // returns a wildcard-bounded builder type, and only the explicit List<FdAccountRole>
        // target type here (via Collectors.<T>toList() inference) resolves the wildcard
        // capture cleanly; Stream.toList() infers too early and fails to compile.
        return initialRoles.stream()
                .map(role -> FdAccountRole.builder()
                        .fdaId(fdaId)
                        .custId(role.custId())
                        .roleTyp(role.roleType().name())
                        .isPrimary(Boolean.TRUE.equals(role.isPrimary()))
                        .efctvDt(efctvDt)
                        .build())
                .collect(Collectors.toList());
    }

    private String firstNonBlank(String requested, String fallback) {
        return StringUtils.hasText(requested) ? requested : fallback;
    }
}
