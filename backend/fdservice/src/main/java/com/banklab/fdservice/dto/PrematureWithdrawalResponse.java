package com.banklab.fdservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response of POST /fd-accounts/{accountId}/withdraw — the lab manual L15 response
 * fields (status, message, withdrawalAmount, penaltyApplied) plus the closed account.
 *
 * @param interestEarned   all INTEREST posted over the account's life, the penalty base
 * @param finalInterest    interest accrued since the last boundary, credited at closure
 * @param penaltyApplied   PENALTY posted
 * @param withdrawalAmount WITHDRAWAL paid to the customer
 */
public record PrematureWithdrawalResponse(
        String status,
        String message,
        LocalDate withdrawalDate,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal interestEarned,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal finalInterest,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal penaltyApplied,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal withdrawalAmount,
        FdAccount account,
        UUID txnGrpId,
        List<Long> transactionIds,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal taxDeducted) {
}
