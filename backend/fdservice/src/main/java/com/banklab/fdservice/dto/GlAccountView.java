package com.banklab.fdservice.dto;

import java.math.BigDecimal;

/**
 * FD_GL_ACCOUNTS row as returned by GET /gl-accounts.
 */
public record GlAccountView(
        String glCd,
        String name,
        String type,
        String currencyCode,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal currentBalance,
        Boolean active) {
}
