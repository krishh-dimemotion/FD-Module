package com.banklab.fdservice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.banklab.fdservice.dto.AccountStatus;

/**
 * Business rules for closing an FD before maturity (lab manual L15). Pure — callers
 * supply the numbers.
 *
 * Penalty model, per the lab manual's applyPenalty(): a percentage of the interest
 * earned up to the withdrawal date ({@code fd.premature-withdrawal.penalty-pct},
 * default 1%), plus an optional flat charge ({@code fd.premature-withdrawal.flat-fee},
 * default 0). Lifetime interest includes SIMPLE interest already paid out; its
 * penalty can reduce principal. There is no minimum holding period. This retains
 * the handoff's option A until Group 2 supplies an agreed product fee contract.
 */
@Component
public class PrematureWithdrawalPolicy {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final BigDecimal penaltyPct;
    private final BigDecimal flatFee;

    public PrematureWithdrawalPolicy(
            @Value("${fd.premature-withdrawal.penalty-pct}") BigDecimal penaltyPct,
            @Value("${fd.premature-withdrawal.flat-fee}") BigDecimal flatFee) {
        if (penaltyPct == null || penaltyPct.signum() < 0 || flatFee == null || flatFee.signum() < 0) {
            throw new IllegalArgumentException("Premature withdrawal penalty settings must be zero or positive");
        }
        this.penaltyPct = penaltyPct;
        this.flatFee = flatFee;
    }

    /**
     * Rejects the withdrawal unless the account is ACTIVE and the withdrawal date is on
     * or after the value date and strictly before maturity (on/after maturity it's a
     * maturity closure, not a premature one).
     */
    public void validateEligibility(String status, LocalDate valueDate, LocalDate maturityDate,
            LocalDate withdrawalDate) {
        if (!AccountStatus.ACTIVE.name().equals(status)) {
            throw new AccountNotWithdrawableException(AccountNotWithdrawableException.ACCOUNT_NOT_ACTIVE,
                    "Account is " + status + "; only ACTIVE accounts can be withdrawn");
        }
        if (withdrawalDate.isBefore(valueDate)) {
            throw new AccountNotWithdrawableException(AccountNotWithdrawableException.BEFORE_VALUE_DATE,
                    "Withdrawal date " + withdrawalDate + " is before the value date " + valueDate);
        }
        if (!withdrawalDate.isBefore(maturityDate)) {
            throw new AccountNotWithdrawableException(AccountNotWithdrawableException.ACCOUNT_MATURED,
                    "Account matured on " + maturityDate + "; it is closed by maturity processing, not withdrawal");
        }
    }

    /**
     * Penalty = interestEarned × pct/100 + flat fee, rounded to currency decimals and
     * capped at the balance so the payout never goes negative.
     */
    public BigDecimal penalty(BigDecimal interestEarned, BigDecimal balance, int currencyDecimals) {
        BigDecimal onInterest = interestEarned.max(BigDecimal.ZERO).multiply(penaltyPct)
                .divide(HUNDRED);
        BigDecimal total = onInterest.add(flatFee).setScale(currencyDecimals, RoundingMode.HALF_UP);
        return total.min(balance.setScale(currencyDecimals, RoundingMode.HALF_UP));
    }
}
