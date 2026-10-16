package com.banklab.fdservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Builder;

/**
 * Matches openapi schema FdAccount — the account detail/creation response
 * (GET/POST /fd-accounts, GET /fd-accounts/{accountId}).
 *
 * A record for the usual reasons (immutable, no hand-written accessors/equals), but
 * still built through Lombok's builder rather than the canonical constructor —
 * with 20+ fields a positional constructor call would be unreadable and error-prone
 * (e.g. two adjacent BigDecimal or LocalDate fields silently swapped).
 */
@Builder
public record FdAccount(
        String fdaId,
        String acctNum,
        String custId,
        String productCode,
        String rateId,
        String categoryCd,
        String currencyCode,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal principalAmt,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal principalBal,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal accruedIntAmt,
        BigDecimal intRt,
        InterestType interestType,
        String compoundFreq,
        String payoutFreq,
        String dayCountConv,
        Integer tenureMonths,
        LocalDate openDt,
        LocalDate valueDt,
        LocalDate matDt,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal matAmt,
        MaturityInstruction matInstruction,
        AccountStatus status,
        LocalDate lastAccrualDt,
        LocalDate lastCaptlzDt,
        LocalDate closureDt,
        String renewedFromId,
        String custNameSnap,
        String prdNameSnap,
        Integer ccyDecimals,
        Boolean taxEnabled) {
}
