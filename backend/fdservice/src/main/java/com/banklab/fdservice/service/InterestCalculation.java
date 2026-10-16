package com.banklab.fdservice.service;

import java.math.BigDecimal;

/**
 * Result of {@link InterestCalculator#calculate}.
 *
 * @param principal       the opening principal the period started with
 * @param totalInterest   all interest earned over the period, at currency decimals
 * @param interestPaidOut the part of totalInterest paid out at boundaries during the
 *                        period (SIMPLE with a periodic payout frequency; else zero)
 * @param maturityValue   what the deposit is worth at period end:
 *                        principal + totalInterest - interestPaidOut
 * @param days            calendar days in the period
 */
public record InterestCalculation(
        BigDecimal principal,
        BigDecimal totalInterest,
        BigDecimal interestPaidOut,
        BigDecimal maturityValue,
        long days,
        BigDecimal taxAmount) {
    public InterestCalculation(BigDecimal principal,BigDecimal interest,BigDecimal paid,BigDecimal value,long days) {
        this(principal,interest,paid,value,days,BigDecimal.ZERO);
    }
}
