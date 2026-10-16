package com.banklab.fdservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** Read-only projection of full closure on the current business date. */
public record WithdrawalQuoteResponse(
        @NotNull LocalDate withdrawalDate,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        @NotNull @PositiveOrZero BigDecimal interestEarned,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        @NotNull @PositiveOrZero BigDecimal finalInterest,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        @NotNull @PositiveOrZero BigDecimal penaltyApplied,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        @NotNull @PositiveOrZero BigDecimal withdrawalAmount,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        @PositiveOrZero BigDecimal taxDeducted) {
    public WithdrawalQuoteResponse(LocalDate date,BigDecimal interest,BigDecimal last,BigDecimal penalty,BigDecimal amount) {
        this(date,interest,last,penalty,amount,BigDecimal.ZERO);
    }
}
