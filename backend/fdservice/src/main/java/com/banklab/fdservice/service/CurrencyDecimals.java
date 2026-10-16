package com.banklab.fdservice.service;

import java.util.Currency;
import java.util.Locale;

/** ISO minor units; missing legacy snapshots retain the documented INR default. */
public final class CurrencyDecimals {
    public static final int DEFAULT_DECIMALS = 2;
    private CurrencyDecimals() { }
    public static String normalize(String code) {
        if (code == null || code.isBlank()) throw new InvalidPrincipalException("Currency code is required");
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        try {
            int digits = Currency.getInstance(normalized).getDefaultFractionDigits();
            if (digits < 0 || digits > 4) throw new IllegalArgumentException();
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new InvalidPrincipalException("Unsupported ISO currency: " + code);
        }
    }
    public static int forCurrency(String code) {
        return code == null || code.isBlank() ? DEFAULT_DECIMALS
            : Currency.getInstance(normalize(code)).getDefaultFractionDigits();
    }
}
