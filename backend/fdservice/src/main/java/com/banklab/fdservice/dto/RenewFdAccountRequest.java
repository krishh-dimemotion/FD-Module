package com.banklab.fdservice.dto;

import jakarta.validation.constraints.Positive;

/** Optional renewal term override for a maturity renewal. */
public record RenewFdAccountRequest(@Positive Integer tenureMonths) {
}
