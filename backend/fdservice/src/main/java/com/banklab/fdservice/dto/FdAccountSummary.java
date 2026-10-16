package com.banklab.fdservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Matches openapi schema FdAccountSummary (GET /fd-accounts search results).
 */
public record FdAccountSummary(
        String fdaId,
        String acctNum,
        String status,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal principalBal,
        LocalDate matDt,
        String productCode,
        String custNameSnap,
        String currencyCode,
        Integer ccyDecimals) {
}
