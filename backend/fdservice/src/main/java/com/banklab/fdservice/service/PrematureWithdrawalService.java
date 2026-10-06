package com.banklab.fdservice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import com.banklab.fdservice.dto.AccountStatus;
import com.banklab.fdservice.dto.FdTxnType;
import com.banklab.fdservice.dto.PrematureWithdrawalRequest;
import com.banklab.fdservice.dto.PrematureWithdrawalResponse;
import com.banklab.fdservice.dto.WithdrawalQuoteResponse;
import com.banklab.fdservice.entity.FdAccount;
import com.banklab.fdservice.repository.FdAccountRepository;
import com.banklab.fdservice.repository.FdTransactionRepository;

import lombok.RequiredArgsConstructor;

/**
 * Premature closure (OpenAPI processEarlyWithdrawal, lab manual L15), atomically:
 * accrue up to the business date, credit that interest, debit the penalty, pay the
 * rest out as WITHDRAWAL, and close the account as CLOSED. All postings share one
 * FDT_TXN_GRP_ID. Only full withdrawal is supported.
 */
@Service
@RequiredArgsConstructor
public class PrematureWithdrawalService {

    private final FdAccountRepository accountRepository;
    private final FdTransactionRepository transactionRepository;
    private final BusinessClockService businessClock;
    private final InterestPostingService interestPosting;
    private final PrematureWithdrawalPolicy policy;
    private final FdLedgerService ledger;
    private final InterestCalculator calculator;

    /** A consistent snapshot of account and ledger; no entity changes or ledger calls. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WithdrawalQuoteResponse quote(UUID fdaId) {
        FdAccount account = accountRepository.findById(fdaId)
                .orElseThrow(() -> new FdAccountNotFoundException(fdaId));
        return calculate(account, businessClock.currentBusinessDate());
    }

    @Transactional
    public PrematureWithdrawalResponse withdraw(UUID fdaId, PrematureWithdrawalRequest request) {
        FdAccount account = accountRepository.lockById(fdaId).orElseThrow(() -> new FdAccountNotFoundException(fdaId));
        LocalDate withdrawalDate = businessClock.currentBusinessDate();
        // TODO G1: enforce owner or BANK_OFFICER here and on quote once JWT claims exist.
        WithdrawalQuoteResponse quote = calculate(account, withdrawalDate);
        requireFullWithdrawal(account, request);
        requireUnchangedQuote(quote, request);

        String userId = FdLedgerService.API_USER;
        UUID group = UUID.randomUUID();
        List<Long> transactionIds = new ArrayList<>();
        String remarks = request == null ? null : request.remarks();
        BigDecimal finalInterest = BigDecimal.ZERO;
        if (interestPosting.hasContractedRate(account)) {
            interestPosting.bringAccrualTo(account, withdrawalDate, withdrawalDate, userId);
            finalInterest = interestPosting.settleAccruedInterest(account, withdrawalDate, withdrawalDate, group,
                    userId);
        }

        if (finalInterest.compareTo(quote.finalInterest()) != 0
                || account.getPrincipalBal().compareTo(quote.withdrawalAmount().add(quote.penaltyApplied())) != 0) {
            throw new IllegalStateException("Withdrawal postings disagree with the calculated quote");
        }
        // Final INTEREST belongs to this closure group; catch-up boundaries have their own groups.
        transactionRepository.findByFdaIdOrderByFdtIdAsc(fdaId).stream()
                .filter(txn -> group.equals(txn.getTxnGrpId()))
                .forEach(txn -> transactionIds.add(txn.getFdtId()));
        BigDecimal penalty = quote.penaltyApplied();
        if (penalty.signum() > 0) {
            // When the fee consumes the balance there is no positive WITHDRAWAL row;
            // keep the closure reason on the PENALTY row in that case.
            transactionIds.add(ledger.postPenalty(account, penalty, withdrawalDate, group, userId,
                    quote.withdrawalAmount().signum() == 0 ? remarks : null).getFdtId());
        }

        BigDecimal payout = quote.withdrawalAmount();
        if (payout.signum() > 0) {
            transactionIds.add(ledger.postWithdrawal(account, payout, withdrawalDate, group, userId, remarks).getFdtId());
        }

        account.setSts(AccountStatus.CLOSED.name());
        account.setClosureDt(withdrawalDate);
        account.stampAudit(userId, "U");
        // TODO maturity team: emit PREMATURE_CLOSURE when its notification writer exists.

        return new PrematureWithdrawalResponse(
                "success",
                "FD account closed before maturity",
                withdrawalDate,
                quote.interestEarned(),
                quote.finalInterest(),
                penalty,
                payout,
                FdAccountMapper.toResponse(account),
                group,
                List.copyOf(transactionIds));
    }

    /** The single calculation for both preview and locked execution. */
    private WithdrawalQuoteResponse calculate(FdAccount account, LocalDate withdrawalDate) {
        policy.validateEligibility(account.getSts(), account.getValueDt(), account.getMatDt(), withdrawalDate);
        requireAccrualNotAhead(account, withdrawalDate);
        int decimals = account.getCcyDecimals() != null
                ? account.getCcyDecimals() : CurrencyDecimals.forCurrency(account.getCcyCd());
        BigDecimal balance = account.getPrincipalBal();
        BigDecimal finalInterest = BigDecimal.ZERO;
        BigDecimal interestEarned = transactionRepository.sumAmountByType(account.getFdaId(), FdTxnType.INTEREST.name());
        if (interestPosting.hasContractedRate(account)) {
            InterestTerms terms = interestPosting.termsOf(account);
            decimals = terms.currencyDecimals();
            AccrualPlan plan = calculator.planAccrual(AccountInterestState.from(account), terms, withdrawalDate);
            BigDecimal accrued = plan.noOp()
                    ? (account.getAccruedIntAmt() == null ? BigDecimal.ZERO : account.getAccruedIntAmt())
                    : plan.accruedInterest();
            finalInterest = terms.roundToCurrency(accrued);
            balance = plan.closingPrincipalBalance().add(finalInterest);
            interestEarned = interestEarned.add(finalInterest);
            for (InterestEvent event : plan.events()) {
                interestEarned = interestEarned.add(event.amount());
            }
        }
        BigDecimal penalty = policy.penalty(interestEarned, balance, decimals);
        return new WithdrawalQuoteResponse(withdrawalDate,
                interestEarned.setScale(decimals, RoundingMode.HALF_UP),
                finalInterest.setScale(decimals, RoundingMode.HALF_UP), penalty,
                balance.subtract(penalty).setScale(decimals, RoundingMode.HALF_UP));
    }

    private static void requireUnchangedQuote(WithdrawalQuoteResponse actual, PrematureWithdrawalRequest request) {
        if (request == null || request.expectedQuote() == null) {
            return; // Preserve existing direct API clients.
        }
        WithdrawalQuoteResponse expected = request.expectedQuote();
        if (!actual.withdrawalDate().equals(expected.withdrawalDate())
                || actual.interestEarned().compareTo(expected.interestEarned()) != 0
                || actual.finalInterest().compareTo(expected.finalInterest()) != 0
                || actual.penaltyApplied().compareTo(expected.penaltyApplied()) != 0
                || actual.withdrawalAmount().compareTo(expected.withdrawalAmount()) != 0) {
            throw new AccountNotWithdrawableException(AccountNotWithdrawableException.QUOTE_CHANGED,
                    "The withdrawal quote has changed; request a new quote and confirm it again");
        }
    }

    /**
     * The withdrawal day itself earns no interest. If interest for that day or later is
     * already accrued (the business clock was moved back after an EOD), closing now would
     * pay interest for days the money isn't held.
     */
    private static void requireAccrualNotAhead(FdAccount account, LocalDate withdrawalDate) {
        if (account.getLastAccrualDt() != null && !account.getLastAccrualDt().isBefore(withdrawalDate)) {
            throw new AccountNotWithdrawableException(AccountNotWithdrawableException.ACCRUAL_AHEAD_OF_BUSINESS_DATE,
                    "Interest is already accrued for " + account.getLastAccrualDt()
                            + ", on or after the business date " + withdrawalDate);
        }
    }

    private static void requireFullWithdrawal(FdAccount account, PrematureWithdrawalRequest request) {
        if (request == null || request.amount() == null) {
            return;
        }
        if (request.amount().compareTo(account.getPrincipalBal()) != 0) {
            throw new AccountNotWithdrawableException(AccountNotWithdrawableException.PARTIAL_NOT_SUPPORTED,
                    "Only full premature withdrawal is supported; omit amount or pass the full balance "
                            + account.getPrincipalBal());
        }
    }
}
