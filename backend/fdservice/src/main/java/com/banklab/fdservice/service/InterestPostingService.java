package com.banklab.fdservice.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.banklab.fdservice.dto.AccountStatus;
import com.banklab.fdservice.dto.InterestType;
import com.banklab.fdservice.entity.FdAccount;
import com.banklab.fdservice.repository.FdAccountRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-account work of the interest batch: plans with {@link InterestCalculator},
 * writes through {@link FdLedgerService}.
 *
 * {@link #accrue} and {@link #mature} each run in their own transaction
 * (REQUIRES_NEW) holding a row lock on the account, so one account's account update,
 * FD_TRANSACTIONS rows and GL entries commit or roll back together, and a failure on
 * one account never undoes another's work. Both re-check their idempotency guard
 * under the lock, so a duplicate or concurrent trigger for the same business date
 * finds nothing to do.
 *
 * <h2>Accrual accounting</h2>
 * Interest is recognised in the GL every day it is accrued (Dr interest expense /
 * Cr interest payable), rounded to currency decimals, so FD_INT_PAYABLE always equals
 * the rounded FDA_ACCRUED_INT_AMT of the ACTIVE accounts. When interest is credited or
 * paid out it moves out of interest payable rather than being expensed again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterestPostingService {

    private final FdAccountRepository accountRepository;
    private final InterestCalculator calculator;
    private final FdLedgerService ledger;

    @org.springframework.beans.factory.annotation.Autowired
    private com.banklab.fdservice.tax.TaxService taxes;

    private com.banklab.fdservice.tax.TaxService.Session taxSession(FdAccount account,LocalDate from,LocalDate to,boolean write) {
        return taxes == null ? com.banklab.fdservice.tax.TaxService.Session.disabled() : taxes.session(account,from,to,write);
    }

    @Value("${fd.interest.default-interest-type}")
    private InterestType defaultInterestType;

    /**
     * ACCRUAL job step for one account: accrues interest for every day up to and
     * including {@code businessDate} (never past maturity). Returns false when there was
     * nothing to do.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean accrue(UUID fdaId, LocalDate businessDate) {
        FdAccount account = lock(fdaId);
        if (!AccountStatus.ACTIVE.name().equals(account.getSts())) {
            return false;
        }
        LocalDate target = InterestCalculator.endOfDayAccrualTarget(businessDate, account.getMatDt());
        return bringAccrualTo(account, target, businessDate, FdLedgerService.BATCH_USER);
    }

    /**
     * MATURITY job step for one account: accrue through the maturity date, credit the
     * remaining accrued interest, pay the whole balance out and close the account as
     * MATURED_CLOSED. Accounts not yet matured or no longer ACTIVE are left alone.
     *
     * Per the OpenAPI renewFdAccount contract, renewal is customer-initiated before the
     * batch runs; an account still ACTIVE here is closed as a straight payout whatever
     * its FDA_MAT_INSTRUCTION says.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean mature(UUID fdaId, LocalDate businessDate) {
        FdAccount account = lock(fdaId);
        if (!AccountStatus.ACTIVE.name().equals(account.getSts()) || account.getMatDt().isAfter(businessDate)) {
            return false;
        }

        prepareClosureTax(account,account.getMatDt());
        UUID closureGroup = UUID.randomUUID();
        if (hasContractedRate(account)) {
            bringAccrualTo(account, account.getMatDt(), businessDate, FdLedgerService.BATCH_USER);
            settleAccruedInterest(account, account.getMatDt(), businessDate, closureGroup,
                    FdLedgerService.BATCH_USER);
        } else {
            // Booked without a rate (rateId is optional at creation): nothing to calculate,
            // but the principal must still be released at maturity rather than stay stuck.
            log.warn("Account {} has no contracted rate; maturing with principal only", fdaId);
        }

        BigDecimal payout = account.getPrincipalBal();
        if (payout.signum() > 0) {
            ledger.postMaturityPayout(account, payout, businessDate, closureGroup, FdLedgerService.BATCH_USER);
        }
        account.setSts(AccountStatus.MATURED_CLOSED.name());
        account.setClosureDt(businessDate);
        account.stampAudit(FdLedgerService.BATCH_USER, "U");
        return true;
    }

    /**
     * Brings a locked account's accrual up to {@code through} (exclusive): recognises the
     * newly accrued interest in the GL, posts any capitalization or payout boundaries
     * passed on the way, then stores the new accrued amount and guard dates. Must run
     * inside the caller's transaction. Returns false if the account was already accrued
     * that far.
     */
    public boolean bringAccrualTo(FdAccount account, LocalDate through, LocalDate businessDate, String userId) {
        InterestTerms terms = termsOf(account);
        AccrualPlan initial = calculator.planAccrual(AccountInterestState.from(account), terms, through);
        if(initial.noOp()) return false;
        var taxableEvents=initial.events().stream().filter(e->e.amount().signum()>0).toList();
        var tax = taxableEvents.isEmpty() ? com.banklab.fdservice.tax.TaxService.Session.disabled()
                : taxSession(account,taxableEvents.getFirst().boundaryDate(),taxableEvents.getLast().boundaryDate(),true);
        AccrualPlan plan = calculator.planAccrual(AccountInterestState.from(account), terms, through,tax::assess);
        if (plan.noOp()) {
            return false;
        }
        if (account.getPrincipalBal() == null) {
            account.setPrincipalBal(account.getPrincipalAmt());
        }

        // Interest of the current period already recognised in the GL by earlier runs.
        BigDecimal recognised = terms.roundToCurrency(accruedOf(account));
        for (InterestEvent event : plan.events()) {
            recogniseAccrual(account, event.amount().subtract(recognised), event.boundaryDate(), businessDate, userId);
            recognised = BigDecimal.ZERO;
            if (event.amount().signum() == 0) {
                continue;
            }
            UUID group = UUID.randomUUID();
            var source = switch (event.type()) {
                case CAPITALIZATION -> ledger.postInterestCapitalization(account, event.amount(),
                        event.boundaryDate(), businessDate, group, userId);
                case PAYOUT -> ledger.postInterestPayout(account, event.amount(), event.boundaryDate(),
                        businessDate, group, userId);
            };
            postAssessment(account,tax,source,event.taxAmount(),event.boundaryDate(),businessDate,group,userId,
                    event.type()==InterestEventType.CAPITALIZATION);
        }
        recogniseAccrual(account, terms.roundToCurrency(plan.accruedInterest()).subtract(recognised),
                plan.accruedThrough(), businessDate, userId);

        if (account.getPrincipalBal().compareTo(plan.closingPrincipalBalance()) != 0) {
            throw new IllegalStateException("Ledger balance " + account.getPrincipalBal()
                    + " disagrees with planned balance " + plan.closingPrincipalBalance()
                    + " for account " + account.getAcctNum());
        }

        account.setAccruedIntAmt(plan.accruedInterest());
        account.setLastAccrualDt(plan.lastAccrualDate());
        account.setLastCaptlzDt(plan.lastSettlementDate());
        account.stampAudit(userId, "U");
        return true;
    }

    /**
     * Credits FDA_ACCRUED_INT_AMT (rounded to currency decimals, already recognised in
     * interest payable) to the deposit as a final INTEREST posting and resets the
     * accrual. Used at maturity and premature closure. Returns the amount credited.
     */
    public BigDecimal settleAccruedInterest(FdAccount account, LocalDate valueDate, LocalDate businessDate,
            UUID txnGroupId, String userId) {
        InterestTerms terms = termsOf(account);
        BigDecimal interest = terms.roundToCurrency(accruedOf(account));
        if (interest.signum() > 0) {
            var tax = taxSession(account,valueDate,valueDate,true);
            BigDecimal deduction = tax.assess(interest,valueDate);
            var source = ledger.postInterestCapitalization(account, interest, valueDate, businessDate, txnGroupId, userId);
            postAssessment(account,tax,source,deduction,valueDate,businessDate,txnGroupId,userId,true);
            account.setLastCaptlzDt(valueDate);
        }
        account.setAccruedIntAmt(BigDecimal.ZERO.setScale(InterestCalculator.ACCRUAL_SCALE));
        return interest;
    }

    private void postAssessment(FdAccount account,com.banklab.fdservice.tax.TaxService.Session tax,
            com.banklab.fdservice.entity.FdTransaction source,BigDecimal amount,LocalDate valueDate,
            LocalDate businessDate,UUID group,String user,boolean capitalized) {
        var decision=tax.next();
        if(decision==null) return;
        Long taxId=null;
        if(amount.signum()>0) taxId=ledger.postTax(account,amount,decision.rate(),valueDate,businessDate,group,user,capitalized).getFdtId();
        tax.record(decision,source.getFdtId(),taxId);
    }

    /** Acquire every year needed for closure before taking any GL locks. */
    public void prepareClosureTax(FdAccount account,LocalDate through) {
        if(!Boolean.TRUE.equals(account.getTaxEnabled()) || !hasContractedRate(account)) return;
        InterestTerms terms=termsOf(account);
        AccrualPlan plan=calculator.planAccrual(AccountInterestState.from(account),terms,through);
        var dates=new java.util.ArrayList<LocalDate>();
        plan.events().stream().filter(e->e.amount().signum()>0).forEach(e->dates.add(e.boundaryDate()));
        BigDecimal accrued=plan.noOp()?accruedOf(account):plan.accruedInterest();
        if(terms.roundToCurrency(accrued).signum()>0) dates.add(through);
        if(!dates.isEmpty()) taxSession(account,dates.getFirst(),dates.getLast(),true);
    }

    /** Read-only simulation of catch-up boundaries and the final partial settlement. */
    public ClosureProjection previewClosure(FdAccount account,LocalDate through) {
        return previewClosure(account,through,false);
    }
    public ClosureProjection previewClosure(FdAccount account,LocalDate through,boolean locked) {
        InterestTerms terms=termsOf(account);
        AccrualPlan initial=calculator.planAccrual(AccountInterestState.from(account),terms,through);
        var dates=new java.util.ArrayList<LocalDate>();
        initial.events().stream().filter(e->e.amount().signum()>0).forEach(e->dates.add(e.boundaryDate()));
        if(terms.roundToCurrency(initial.noOp()?accruedOf(account):initial.accruedInterest()).signum()>0) dates.add(through);
        var tax=dates.isEmpty()?com.banklab.fdservice.tax.TaxService.Session.disabled():taxSession(account,dates.getFirst(),dates.getLast(),locked);
        AccrualPlan plan=calculator.planAccrual(AccountInterestState.from(account),terms,through,tax::assess);
        BigDecimal accrued=plan.noOp()?accruedOf(account):plan.accruedInterest();
        BigDecimal interest=terms.roundToCurrency(accrued);
        BigDecimal finalTax=interest.signum()>0?tax.assess(interest,through):BigDecimal.ZERO;
        BigDecimal totalTax=finalTax;
        for(var event:plan.events()) totalTax=totalTax.add(event.taxAmount());
        return new ClosureProjection(plan,interest,finalTax,totalTax,
            plan.closingPrincipalBalance().add(interest).subtract(finalTax));
    }
    public record ClosureProjection(AccrualPlan plan,BigDecimal finalInterest,BigDecimal finalTax,
            BigDecimal totalTax,BigDecimal closingBalance) { }

    /** False for accounts booked without a rateId — they can't accrue, only be closed. */
    public boolean hasContractedRate(FdAccount account) {
        return account.getIntRt() != null;
    }

    public InterestTerms termsOf(FdAccount account) {
        return InterestTerms.fromAccount(account, defaultInterestType);
    }

    private void recogniseAccrual(FdAccount account, BigDecimal amount, LocalDate accruedThrough,
            LocalDate businessDate, String userId) {
        if (amount.signum() != 0) {
            ledger.postInterestAccrual(account, amount, accruedThrough, businessDate, userId);
        }
    }

    private static BigDecimal accruedOf(FdAccount account) {
        return account.getAccruedIntAmt() == null ? BigDecimal.ZERO : account.getAccruedIntAmt();
    }

    private FdAccount lock(UUID fdaId) {
        return accountRepository.lockById(fdaId).orElseThrow(() -> new FdAccountNotFoundException(fdaId));
    }
}
