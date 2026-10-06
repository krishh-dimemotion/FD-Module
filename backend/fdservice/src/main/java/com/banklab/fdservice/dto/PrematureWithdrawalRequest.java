package com.banklab.fdservice.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Request body of POST /fd-accounts/{accountId}/withdraw. Only full withdrawal is
 * supported: amount may be omitted, or must equal the current balance.
 */
public record PrematureWithdrawalRequest(
        @Positive BigDecimal amount,
        @Size(max = 255) String remarks,
        @Valid WithdrawalQuoteResponse expectedQuote) {
}
