package com.banklab.fdservice.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banklab.fdservice.dto.GlAccountType;
import com.banklab.fdservice.entity.FdGlAccount;
import com.banklab.fdservice.repository.FdGlAccountRepository;

import lombok.RequiredArgsConstructor;

/**
 * Owns FD_GL_ACCOUNTS: the internal ledger codes fd-service posts to, and their
 * running FDGL_CURRENT_BAL. Codes are configuration (fd.gl.*); the rows are seeded on
 * first use so a fresh fd_db can post immediately.
 *
 * <pre>
 * code (default)   type       used for
 * FD_DEP_LIAB      LIABILITY  money the bank owes FD customers (principal + capitalized interest)
 * FD_INT_EXP       EXPENSE    interest the bank pays on FDs, recognised daily as it accrues
 * FD_INT_PAYABLE   LIABILITY  interest accrued but not yet credited or paid out
 * FD_SETTLEMENT    ASSET      customer settlement / savings clearing — where deposits come from and payouts go
 * FD_PENALTY_INC   INCOME     premature-withdrawal penalties
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class GlAccountService {

    private final FdGlAccountRepository repository;

    @Value("${fd.gl.deposit-liability}")
    private String depositLiabilityCd;

    @Value("${fd.gl.interest-expense}")
    private String interestExpenseCd;

    @Value("${fd.gl.interest-payable}")
    private String interestPayableCd;

    @Value("${fd.gl.settlement}")
    private String settlementCd;

    @Value("${fd.gl.penalty-income}")
    private String penaltyIncomeCd;

    @Value("${fd.gl.currency}")
    private String glCurrency;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public String taxPayableCd() { return "FD_TAX_PAYABLE"; }

    /** Keep configured INR codes; foreign ledgers carry an explicit ISO suffix. */
    public String inCurrency(String baseCode, String currency) {
        if (currency.equals(glCurrency)) return baseCode;
        String code = baseCode + "_" + currency;
        if (repository.existsById(code)) return code;
        FdGlAccount template=repository.findById(baseCode).orElseThrow(()->new GlAccountNotConfiguredException("Missing GL template " + baseCode));
        jdbc.update("""
            INSERT IGNORE INTO FD_GL_ACCOUNTS
            (FDGL_CD,FDGL_NAME,FDGL_TYP,FDGL_CCY_CD,FDGL_CURRENT_BAL,FDGL_IS_ACTIVE,FDGL_EFCTV_DT,USER_ID,CRUD_VALUE)
            VALUES (?,?,?,?,0,true,?,'FD_SYSTEM','C')
            """,code,template.getName(),template.getTyp(),currency,template.getEfctvDt());
        return code;
    }

    public String depositLiabilityCd() {
        return depositLiabilityCd;
    }

    public String interestExpenseCd() {
        return interestExpenseCd;
    }

    public String interestPayableCd() {
        return interestPayableCd;
    }

    public String settlementCd() {
        return settlementCd;
    }

    @Transactional(readOnly = true)
    public boolean exists(String code) {
        return repository.existsById(code);
    }

    public String penaltyIncomeCd() {
        return penaltyIncomeCd;
    }

    @Transactional
    public void ensureDefaults() {
        defaults().forEach((code, definition) -> {
            if (!repository.existsById(code)) {
                FdGlAccount account = FdGlAccount.builder()
                        .fdglCd(code)
                        .name(definition.name())
                        .typ(definition.type().name())
                        .ccyCd(glCurrency)
                        .currentBal(BigDecimal.ZERO.setScale(2))
                        .isActive(true)
                        .efctvDt(LocalDate.now())
                        .build();
                account.stampAudit(FdLedgerService.SYSTEM_USER, "C");
                repository.save(account);
            }
        });
    }

    /**
     * Applies one balanced two-leg posting to the running balances. Both rows are
     * locked in code order, so two concurrent postings touching the same pair of GL
     * accounts can't deadlock.
     */
    @Transactional
    public void applyPosting(String debitCd, String creditCd, BigDecimal amount) {
        String first = debitCd.compareTo(creditCd) <= 0 ? debitCd : creditCd;
        String second = first.equals(debitCd) ? creditCd : debitCd;
        FdGlAccount firstAccount = lockActive(first);
        FdGlAccount secondAccount = lockActive(second);
        FdGlAccount debit = first.equals(debitCd) ? firstAccount : secondAccount;
        FdGlAccount credit = first.equals(debitCd) ? secondAccount : firstAccount;

        debit.setCurrentBal(debit.getCurrentBal().add(signedEffect(debit.getTyp(), true, amount)));
        credit.setCurrentBal(credit.getCurrentBal().add(signedEffect(credit.getTyp(), false, amount)));
        debit.stampAudit(FdLedgerService.SYSTEM_USER, "U");
        credit.stampAudit(FdLedgerService.SYSTEM_USER, "U");
    }

    @Transactional(readOnly = true)
    public List<FdGlAccount> findAll() {
        return repository.findAllByOrderByFdglCdAsc();
    }

    /**
     * Balance effect of one leg: debits raise debit-normal accounts (ASSET, EXPENSE)
     * and lower credit-normal ones (LIABILITY, INCOME); credits do the opposite.
     */
    static BigDecimal signedEffect(String glType, boolean isDebit, BigDecimal amount) {
        boolean debitNormal = GlAccountType.valueOf(glType).isDebitNormal();
        return debitNormal == isDebit ? amount : amount.negate();
    }

    private FdGlAccount lockActive(String code) {
        FdGlAccount account = repository.lockByCode(code).orElseGet(() -> {
            ensureDefaults();
            return repository.lockByCode(code).orElseThrow(() -> new GlAccountNotConfiguredException(
                    "No FD_GL_ACCOUNTS row configured for GL code '" + code + "'"));
        });
        if (!Boolean.TRUE.equals(account.getIsActive())) {
            throw new GlAccountNotConfiguredException("GL account '" + code + "' is not active");
        }
        return account;
    }

    private Map<String, GlDefinition> defaults() {
        Map<String, GlDefinition> defaults = new LinkedHashMap<>();
        defaults.put(depositLiabilityCd, new GlDefinition("Fixed Deposits - Customer Liability", GlAccountType.LIABILITY));
        defaults.put(interestExpenseCd, new GlDefinition("Interest Expense on Fixed Deposits", GlAccountType.EXPENSE));
        defaults.put(interestPayableCd, new GlDefinition("Interest Payable on Fixed Deposits", GlAccountType.LIABILITY));
        defaults.put(settlementCd, new GlDefinition("Customer Settlement Clearing", GlAccountType.ASSET));
        defaults.put(taxPayableCd(), new GlDefinition("Demo Withholding Tax Payable", GlAccountType.LIABILITY));
        defaults.put(penaltyIncomeCd, new GlDefinition("Premature Withdrawal Penalty Income", GlAccountType.INCOME));
        return defaults;
    }

    private record GlDefinition(String name, GlAccountType type) {
    }
}
