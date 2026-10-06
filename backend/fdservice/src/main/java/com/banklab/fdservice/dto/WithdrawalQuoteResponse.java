package com.banklab.fdservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** Read-only projection of full closure on the current business date. */
public record WithdrawalQuoteResponse(
        @NotNull LocalDate withdrawalDate,
        @NotNull @PositiveOrZero BigDecimal interestEarned,
        @NotNull @PositiveOrZero BigDecimal finalInterest,
        @NotNull @PositiveOrZero BigDecimal penaltyApplied,
        @NotNull @PositiveOrZero BigDecimal withdrawalAmount) {
}
