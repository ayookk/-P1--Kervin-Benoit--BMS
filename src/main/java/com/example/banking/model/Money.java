package com.example.banking.model;

import com.example.banking.exception.ValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Immutable money value: never null, never negative, always exactly 2 decimal places. */
public final class Money implements Comparable<Money> {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    private final BigDecimal amount;

    private Money(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("Amount must not be null");
        }
        BigDecimal scaled;
        try {
            // UNNECESSARY = throw instead of rounding, so 10.555 is rejected
            scaled = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new ValidationException("Amount cannot have more than 2 decimal places.");
        }
        if (scaled.signum() < 0) {
            throw new ValidationException("Amount cannot be negative.");
        }
        this.amount = scaled;
    }

    public static Money of(BigDecimal value) {
        return new Money(value);
    }

    /** Parses user input such as "250" or "99.95". */
    public static Money of(String text) {
        if (text == null || text.isBlank()) {
            throw new ValidationException("Amount is required.");
        }
        try {
            return new Money(new BigDecimal(text.trim()));
        } catch (NumberFormatException e) {
            throw new ValidationException("Please enter a valid amount, e.g. 100 or 49.99.");
        }
    }

    public Money add(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money subtract(Money other) {
        BigDecimal result = amount.subtract(other.amount);
        if (result.signum() < 0) {
            throw new IllegalStateException("Subtraction would make money negative");
        }
        return new Money(result);
    }

    public boolean isPositive()               { return amount.signum() > 0; }
    public boolean isZero()                   { return amount.signum() == 0; }
    public boolean isLessThan(Money other)    { return compareTo(other) < 0; }
    public boolean isGreaterThan(Money other) { return compareTo(other) > 0; }

    public BigDecimal toBigDecimal() { return amount; }

    @Override
    public int compareTo(Money other) { return amount.compareTo(other.amount); }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money m && amount.equals(m.amount);
    }

    @Override
    public int hashCode() { return Objects.hash(amount); }

    @Override
    public String toString() { return amount.toPlainString(); }
}