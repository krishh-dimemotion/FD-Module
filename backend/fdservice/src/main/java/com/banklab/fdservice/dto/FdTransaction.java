package com.banklab.fdservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Matches openapi schema FdTransaction (GET /fd-accounts/{accountId}/transactions).
 */
public record FdTransaction(
        Long fdtId,
        String fdaId,
        String txnGrpId,
        String txnType,
        String drCr,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal amt,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal balBefore,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal balAfter,
        String ccyCd,
        LocalDate txnDt,
        LocalDate valueDt,
        LocalDateTime txnTs,
        BigDecimal tdsRt,
        String remarks) {
}
