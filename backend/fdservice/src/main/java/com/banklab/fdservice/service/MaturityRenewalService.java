package com.banklab.fdservice.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banklab.fdservice.dto.AccountStatus;
import com.banklab.fdservice.dto.RenewFdAccountRequest;
import com.banklab.fdservice.entity.FdAccount;
import com.banklab.fdservice.entity.FdAccountRole;
import com.banklab.fdservice.repository.FdAccountRepository;
import com.banklab.fdservice.repository.FdAccountRoleRepository;

import lombok.RequiredArgsConstructor;

/**
 * Customer-initiated renewal at maturity. The old account is retained as an
 * immutable historical contract and the renewed funds form a newly numbered FD.
 */
@Service
@RequiredArgsConstructor
public class MaturityRenewalService {

    private final FdAccountRepository accountRepository;
    private final FdAccountRoleRepository roleRepository;
    private final AccountNumberGeneratorService accountNumberGenerator;
    private final BusinessClockService businessClock;
    private final InterestPostingService interestPosting;
    private final InterestCalculator interestCalculator;
    private final FdLedgerService ledger;

    @Value("${fd.account-number.default-branch-cd}")
    private String defaultBranchCd;

    @Value("${fd.interest.default-interest-type}")
    private com.banklab.fdservice.dto.InterestType defaultInterestType;

    @Transactional
    public com.banklab.fdservice.dto.FdAccount renew(UUID fdaId, RenewFdAccountRequest request) {
        FdAccount oldAccount = accountRepository.lockById(fdaId)
                .orElseThrow(() -> new FdAccountNotFoundException(fdaId));
        LocalDate renewalDate = businessClock.currentBusinessDate();
        requireEligible(oldAccount, renewalDate);

        UUID transactionGroup = UUID.randomUUID();
        if (interestPosting.hasContractedRate(oldAccount)) {
            interestPosting.bringAccrualTo(oldAccount, oldAccount.getMatDt(), renewalDate, FdLedgerService.API_USER);
            interestPosting.settleAccruedInterest(oldAccount, oldAccount.getMatDt(), renewalDate, transactionGroup,
                    FdLedgerService.API_USER);
        }

        BigDecimal renewalAmount = oldAccount.getPrincipalBal();
        if (renewalAmount.signum() <= 0) {
            throw new IllegalStateException("Matured account has no positive balance to renew");
        }

        int tenureMonths = request != null && request.tenureMonths() != null
                ? request.tenureMonths() : oldAccount.getTenureMonths();
        FdAccount renewedAccount = renewedCopy(oldAccount, renewalAmount, tenureMonths, renewalDate);
        FdAccount savedRenewed = accountRepository.saveAndFlush(renewedAccount);
        roleRepository.saveAll(copyRoles(oldAccount.getFdaId(), savedRenewed.getFdaId(), renewalDate));

        ledger.postRenewalTransferOut(oldAccount, renewalAmount, renewalDate, transactionGroup, FdLedgerService.API_USER);
        ledger.postRenewalTransferIn(savedRenewed, renewalAmount, renewalDate, transactionGroup, FdLedgerService.API_USER);

        oldAccount.setSts(AccountStatus.MATURED_RENEWED.name());
        oldAccount.setClosureDt(renewalDate);
        oldAccount.stampAudit(FdLedgerService.API_USER, "U");
        return FdAccountMapper.toResponse(savedRenewed);
    }

    private void requireEligible(FdAccount account, LocalDate renewalDate) {
        if (!AccountStatus.ACTIVE.name().equals(account.getSts())) {
            throw new AccountNotRenewableException(AccountNotRenewableException.ACCOUNT_NOT_ACTIVE,
                    "Only an ACTIVE FD account can be renewed");
        }
        if (renewalDate.isBefore(account.getMatDt())) {
            throw new AccountNotRenewableException(AccountNotRenewableException.ACCOUNT_NOT_MATURED,
                    "FD account cannot be renewed before maturity date " + account.getMatDt());
        }
    }

    private FdAccount renewedCopy(FdAccount oldAccount, BigDecimal amount, int tenureMonths, LocalDate renewalDate) {
        FdAccount renewed = FdAccount.builder()
                .fdaId(UUID.randomUUID())
                .acctNum(accountNumberGenerator.generateAccountNumber(defaultBranchCd))
                .custId(oldAccount.getCustId())
                .prdCode(oldAccount.getPrdCode())
                .rateId(oldAccount.getRateId())
                .categoryCd(oldAccount.getCategoryCd())
                .ccyCd(oldAccount.getCcyCd())
                .ccyDecimals(oldAccount.getCcyDecimals())
                .principalAmt(amount)
                .principalBal(BigDecimal.ZERO.setScale(amount.scale()))
                .accruedIntAmt(BigDecimal.ZERO)
                .intRt(oldAccount.getIntRt())
                .intTyp(oldAccount.getIntTyp())
                .compoundFreq(oldAccount.getCompoundFreq())
                .payoutFreq(oldAccount.getPayoutFreq())
                .dayCountConv(oldAccount.getDayCountConv())
                .tdsRt(oldAccount.getTdsRt())
                .tenureMonths(tenureMonths)
                .openDt(renewalDate)
                .valueDt(renewalDate)
                .matDt(renewalDate.plusMonths(tenureMonths))
                .matInstruction(oldAccount.getMatInstruction())
                .sts(AccountStatus.ACTIVE.name())
                .renewedFromId(oldAccount.getFdaId())
                .custNameSnap(oldAccount.getCustNameSnap())
                .prdNameSnap(oldAccount.getPrdNameSnap())
                .efctvDt(renewalDate)
                .build();
        renewed.setMatAmt(interestCalculator.calculate(amount,
                InterestTerms.fromAccount(renewed, defaultInterestType), renewed.getValueDt(), renewed.getMatDt())
                .maturityValue());
        renewed.stampAudit(FdLedgerService.API_USER, "C");
        return renewed;
    }

    private List<FdAccountRole> copyRoles(UUID oldAccountId, UUID newAccountId, LocalDate renewalDate) {
        return roleRepository.findByFdaId(oldAccountId).stream()
                .map(role -> FdAccountRole.builder()
                        .fdaId(newAccountId)
                        .custId(role.getCustId())
                        .prdRoleId(role.getPrdRoleId())
                        .roleTyp(role.getRoleTyp())
                        .isPrimary(role.getIsPrimary())
                        .efctvDt(renewalDate)
                        .build())
                .collect(Collectors.toList());
    }
}
