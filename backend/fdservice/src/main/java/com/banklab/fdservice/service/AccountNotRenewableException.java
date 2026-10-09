package com.banklab.fdservice.service;

import lombok.Getter;

/** Raised when an FD cannot be renewed through the maturity lifecycle. */
@Getter
public class AccountNotRenewableException extends RuntimeException {

    public static final String ACCOUNT_NOT_MATURED = "ACCOUNT_NOT_MATURED";
    public static final String ACCOUNT_NOT_ACTIVE = "ACCOUNT_NOT_ACTIVE";

    private final String code;

    public AccountNotRenewableException(String code, String message) {
        super(message);
        this.code = code;
    }
}
