package com.banklab.fdservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response of POST /fd-accounts/{accountId}/interest-calculations. The first seven
 * fields are the OpenAPI contract; the rest say how the amount was reached.
 */
public record InterestPreviewResponse(
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal principal,
        BigDecimal rate,
        LocalDate periodStart,
        LocalDate periodEnd,
        String dayCountConvention,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal interestAmount,
        String currencyCode,
        InterestType interestType,
        String compoundingFrequency,
        String payoutFrequency,
        long days,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal interestPaidOut,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal maturityValue,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal estimatedTax,
        boolean taxEnabled) {
}
