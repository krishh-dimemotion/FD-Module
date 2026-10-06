package com.banklab.fdservice.service;

import lombok.Getter;

/**
 * Premature withdrawal rejected by a business rule (account not ACTIVE, date before
 * the value date, or the account has already reached maturity). Maps to 409 with
 * {@link #getCode()} as the error code.
 */
@Getter
public class AccountNotWithdrawableException extends RuntimeException {

    public static final String ACCOUNT_NOT_ACTIVE = "ACCOUNT_NOT_ACTIVE";
    public static final String BEFORE_VALUE_DATE = "WITHDRAWAL_BEFORE_VALUE_DATE";
    public static final String ACCOUNT_MATURED = "ACCOUNT_MATURED";
    public static final String PARTIAL_NOT_SUPPORTED = "PARTIAL_WITHDRAWAL_NOT_SUPPORTED";
    public static final String ACCRUAL_AHEAD_OF_BUSINESS_DATE = "ACCRUAL_AHEAD_OF_BUSINESS_DATE";
    public static final String QUOTE_CHANGED = "WITHDRAWAL_QUOTE_CHANGED";

    private final String code;

    public AccountNotWithdrawableException(String code, String message) {
        super(message);
        this.code = code;
    }
}
