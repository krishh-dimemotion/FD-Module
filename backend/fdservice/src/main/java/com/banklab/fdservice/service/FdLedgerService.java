package com.banklab.fdservice.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banklab.fdservice.dto.FdTxnType;
import com.banklab.fdservice.entity.FdAccount;
import com.banklab.fdservice.entity.FdGlEntry;
import com.banklab.fdservice.entity.FdTransaction;
import com.banklab.fdservice.repository.FdGlEntryRepository;
import com.banklab.fdservice.repository.FdTransactionRepository;

import lombok.RequiredArgsConstructor;

/**
 * The shared FD_TRANSACTIONS writer (OpenAPI listFdTransactions: "used internally by
 * Actions 5, 9, 11, 12, 14, 15"). Every customer posting writes, in the caller's
 * transaction:
 * <ol>
 *   <li>one FD_TRANSACTIONS row with FDT_BAL_BEFORE / FDT_BAL_AFTER,</li>
 *   <li>two FD_GL_ENTRIES rows (one D, one C) sharing the transaction's group id, and</li>
 *   <li>the FDGL_CURRENT_BAL change on both GL accounts,</li>
 * </ol>
 * and moves FDA_PRINCIPAL_BAL on the (managed) account entity. Callers save the
 * account; they never touch the ledger tables directly.
 *
 * <pre>
 * posting                  FDT_DR_CR  deposit balance   GL debit          GL credit
 * DEPOSIT                  C          0 → principal     FD_SETTLEMENT     FD_DEP_LIAB
 * (daily interest accrual) —          unchanged         FD_INT_EXP        FD_INT_PAYABLE   GL only, no FDT row
 * INTEREST (capitalized)   C          + interest        FD_INT_PAYABLE    FD_DEP_LIAB
 * INTEREST (paid out)      C          unchanged         FD_INT_PAYABLE    FD_SETTLEMENT
 * PENALTY                  D          − penalty         FD_DEP_LIAB       FD_PENALTY_INC
 * WITHDRAWAL               D          − amount          FD_DEP_LIAB       FD_SETTLEMENT
 * MATURITY_PAYOUT          D          − amount          FD_DEP_LIAB       FD_SETTLEMENT
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class FdLedgerService {

    /** FDA/FDT USER_ID for postings made by the batch. */
    public static final String BATCH_USER = "FD_BATCH";
    /** USER_ID for API-originated postings until Group 1's JWT identity is available. */
    public static final String API_USER = "FD_API";
    /** USER_ID for reference-data seeding. */
    public static final String SYSTEM_USER = "FD_SYSTEM";

    private static final String DEBIT = "D";
    private static final String CREDIT = "C";

    private final FdTransactionRepository transactionRepository;
    private final FdGlEntryRepository glEntryRepository;
    private final GlAccountService glAccounts;

    /** Initial DEPOSIT at account creation (lab manual L16). Balance goes 0 → principal. */
    @Transactional
    public FdTransaction postDeposit(FdAccount account, LocalDate businessDate, UUID txnGroupId, String userId) {
        BigDecimal amount = account.getPrincipalAmt();
        return post(new Posting(account, FdTxnType.DEPOSIT, CREDIT, amount, BigDecimal.ZERO.setScale(2), amount,
                account.getValueDt(), businessDate, txnGroupId, "Initial deposit",
                glAccounts.settlementCd(), glAccounts.depositLiabilityCd(), userId));
    }

    /**
     * Daily interest accrual — recognises interest earned but not yet credited:
     * Dr interest expense / Cr interest payable. GL only: there is no FD_TRANSACTIONS row
     * (the customer sees it as FDA_ACCRUED_INT_AMT), so FDGE_FDT_ID is null. A negative
     * amount reverses interest recognised earlier.
     */
    @Transactional
    public void postInterestAccrual(FdAccount account, BigDecimal amount, LocalDate accruedThrough,
            LocalDate businessDate, String userId) {
        if (amount == null || amount.signum() == 0) {
            return;
        }
        boolean reversal = amount.signum() < 0;
        BigDecimal value = amount.abs();
        String debitCd = reversal ? glAccounts.interestPayableCd() : glAccounts.interestExpenseCd();
        String creditCd = reversal ? glAccounts.interestExpenseCd() : glAccounts.interestPayableCd();
        UUID group = UUID.randomUUID();
        String narrative = (reversal ? "INTEREST_ACCRUAL_REVERSAL " : "INTEREST_ACCRUAL ") + account.getAcctNum()
                + " to " + accruedThrough;

        glEntryRepository.saveAll(List.of(
                glLeg(null, group, debitCd, DEBIT, value, businessDate, narrative, userId),
                glLeg(null, group, creditCd, CREDIT, value, businessDate, narrative, userId)));
        glAccounts.applyPosting(debitCd, creditCd, value);
    }

    /** Accrued interest added to principal at a compounding boundary or at closure. */
    @Transactional
    public FdTransaction postInterestCapitalization(FdAccount account, BigDecimal amount, LocalDate valueDate,
            LocalDate businessDate, UUID txnGroupId, String userId) {
        BigDecimal before = account.getPrincipalBal();
        BigDecimal after = before.add(amount);
        FdTransaction txn = post(new Posting(account, FdTxnType.INTEREST, CREDIT, amount, before, after,
                valueDate, businessDate, txnGroupId, "Interest capitalized for period ending " + valueDate,
                glAccounts.interestPayableCd(), glAccounts.depositLiabilityCd(), userId));
        account.setPrincipalBal(after);
        return txn;
    }

    /** Accrued interest paid out to the customer at a payout boundary; the deposit balance is unchanged. */
    @Transactional
    public FdTransaction postInterestPayout(FdAccount account, BigDecimal amount, LocalDate valueDate,
            LocalDate businessDate, UUID txnGroupId, String userId) {
        BigDecimal balance = account.getPrincipalBal();
        return post(new Posting(account, FdTxnType.INTEREST, CREDIT, amount, balance, balance,
                valueDate, businessDate, txnGroupId, "Interest paid out for period ending " + valueDate,
                glAccounts.interestPayableCd(), glAccounts.settlementCd(), userId));
    }

    @Transactional
    public FdTransaction postPenalty(FdAccount account, BigDecimal amount, LocalDate businessDate, UUID txnGroupId,
            String userId) {
        return postPenalty(account, amount, businessDate, txnGroupId, userId, null);
    }

    @Transactional
    public FdTransaction postPenalty(FdAccount account, BigDecimal amount, LocalDate businessDate, UUID txnGroupId,
            String userId, String remarks) {
        return postDebit(account, FdTxnType.PENALTY, amount, businessDate, txnGroupId,
                remarks == null || remarks.isBlank() ? "Premature withdrawal penalty" : remarks,
                glAccounts.penaltyIncomeCd(), userId);
    }

    @Transactional
    public FdTransaction postWithdrawal(FdAccount account, BigDecimal amount, LocalDate businessDate,
            UUID txnGroupId, String userId) {
        return postWithdrawal(account, amount, businessDate, txnGroupId, userId, null);
    }

    @Transactional
    public FdTransaction postWithdrawal(FdAccount account, BigDecimal amount, LocalDate businessDate,
            UUID txnGroupId, String userId, String remarks) {
        return postDebit(account, FdTxnType.WITHDRAWAL, amount, businessDate, txnGroupId,
                remarks == null || remarks.isBlank() ? "Premature withdrawal - account closed" : remarks,
                glAccounts.settlementCd(), userId);
    }

    @Transactional
    public FdTransaction postMaturityPayout(FdAccount account, BigDecimal amount, LocalDate businessDate,
            UUID txnGroupId, String userId) {
        return postDebit(account, FdTxnType.MATURITY_PAYOUT, amount, businessDate, txnGroupId,
                "Maturity payout on " + businessDate, glAccounts.settlementCd(), userId);
    }

    private FdTransaction postDebit(FdAccount account, FdTxnType type, BigDecimal amount, LocalDate businessDate,
            UUID txnGroupId, String remarks, String creditGlCd, String userId) {
        BigDecimal before = account.getPrincipalBal();
        if (amount.compareTo(before) > 0) {
            throw new IllegalStateException(type + " of " + amount + " exceeds balance " + before
                    + " on account " + account.getAcctNum());
        }
        BigDecimal after = before.subtract(amount);
        FdTransaction txn = post(new Posting(account, type, DEBIT, amount, before, after, businessDate,
                businessDate, txnGroupId, remarks, glAccounts.depositLiabilityCd(), creditGlCd, userId));
        account.setPrincipalBal(after);
        return txn;
    }

    private FdTransaction post(Posting p) {
        if (p.amount() == null || p.amount().signum() <= 0) {
            throw new IllegalArgumentException(p.type() + " amount must be positive, got " + p.amount());
        }

        FdTransaction txn = FdTransaction.builder()
                .fdaId(p.account().getFdaId())
                .txnGrpId(p.txnGroupId())
                .txnTyp(p.type().name())
                .drCr(p.drCr())
                .amt(p.amount())
                .balBefore(p.balanceBefore())
                .balAfter(p.balanceAfter())
                .ccyCd(p.account().getCcyCd())
                .txnDt(p.businessDate())
                .valueDt(p.valueDate())
                .txnTs(LocalDateTime.now())
                .remarks(p.remarks())
                .efctvDt(p.businessDate())
                .build();
        txn.stampAudit(p.userId(), "C");
        FdTransaction saved = transactionRepository.save(txn);

        String narrative = p.type() + " " + p.account().getAcctNum();
        glEntryRepository.saveAll(List.of(
                glLeg(saved.getFdtId(), p.txnGroupId(), p.debitGlCd(), DEBIT, p.amount(), p.businessDate(),
                        narrative, p.userId()),
                glLeg(saved.getFdtId(), p.txnGroupId(), p.creditGlCd(), CREDIT, p.amount(), p.businessDate(),
                        narrative, p.userId())));
        glAccounts.applyPosting(p.debitGlCd(), p.creditGlCd(), p.amount());
        return saved;
    }

    private static FdGlEntry glLeg(Long fdtId, UUID txnGroupId, String glCd, String drCr, BigDecimal amount,
            LocalDate postDate, String narrative, String userId) {
        FdGlEntry entry = FdGlEntry.builder()
                .glCd(glCd)
                .fdtId(fdtId)
                .txnGrpId(txnGroupId)
                .drCr(drCr)
                .amt(amount)
                .postDt(postDate)
                .narrative(narrative)
                .efctvDt(postDate)
                .build();
        entry.stampAudit(userId, "C");
        return entry;
    }

    private record Posting(FdAccount account, FdTxnType type, String drCr, BigDecimal amount,
            BigDecimal balanceBefore, BigDecimal balanceAfter, LocalDate valueDate, LocalDate businessDate,
            UUID txnGroupId, String remarks, String debitGlCd, String creditGlCd, String userId) {
    }
}
