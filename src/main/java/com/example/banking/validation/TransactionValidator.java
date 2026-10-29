package com.example.banking.validation;

import com.example.banking.exception.ValidationException;
import com.example.banking.model.Money;

/** Amount rules for money operations (doc section 21). Money itself already rejects negatives and 3+ decimals. */
public class TransactionValidator {

    private final Money maxAmount;

    public TransactionValidator(Money maxAmount) {
        this.maxAmount = maxAmount;
    }

    public void requireValidAmount(Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw new ValidationException("Amount must be greater than zero.");
        }
        if (amount.isGreaterThan(maxAmount)) {
            throw new ValidationException("Amount cannot exceed $" + maxAmount + " per operation.");
        }
    }
}