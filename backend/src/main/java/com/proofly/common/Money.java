package com.proofly.common;

import java.util.Currency;
import java.util.Objects;

/**
 * Monetary amount strictly represented in integer minor units (e.g., kobo/cents) + ISO-4217 currency.
 * Never uses floating-point or BigDecimal arithmetic in ledger paths.
 */
public record Money(long minorUnits, String currencyCode) {
    public Money {
        Objects.requireNonNull(currencyCode, "currencyCode is required");
        if (currencyCode.length() != 3) {
            throw new IllegalArgumentException("currencyCode must be ISO-4217 3-letter code");
        }
        Currency.getInstance(currencyCode);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(this.minorUnits, other.minorUnits), this.currencyCode);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(this.minorUnits, other.minorUnits), this.currencyCode);
    }

    private void requireSameCurrency(Money other) {
        if (!this.currencyCode.equals(other.currencyCode)) {
            throw new IllegalArgumentException("Currency mismatch: " + this.currencyCode + " vs " + other.currencyCode);
        }
    }
}
