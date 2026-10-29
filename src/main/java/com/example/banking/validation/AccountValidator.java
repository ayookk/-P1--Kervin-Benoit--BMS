package com.example.banking.validation;

import com.example.banking.exception.ValidationException;
import com.example.banking.model.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Rules for opening accounts. Reports every problem at once. No database access. */
public class AccountValidator {

    private static final BigDecimal MAX_RATE = new BigDecimal("100");
    private static final int MAX_TENURE_MONTHS = 360;

    private final Money maxAmount;

    public AccountValidator(Money maxAmount) {
        this.maxAmount = maxAmount;
    }

    public void validateLoanTerms(Money principal, BigDecimal annualRate, int tenureMonths) {
        List<String> errors = new ArrayList<>();

        if (principal == null || !principal.isPositive()) {
            errors.add("Loan amount must be greater than zero.");
        } else if (principal.isGreaterThan(maxAmount)) {
            errors.add("Loan amount cannot exceed $" + maxAmount + ".");
        }
        if (annualRate == null || annualRate.signum() < 0 || annualRate.compareTo(MAX_RATE) > 0) {
            errors.add("Interest rate must be between 0 and 100 percent.");
        } else if (annualRate.stripTrailingZeros().scale() > 2) {
            errors.add("Interest rate can have at most 2 decimal places.");
        }
        if (tenureMonths < 1 || tenureMonths > MAX_TENURE_MONTHS) {
            errors.add("Tenure must be 1 to " + MAX_TENURE_MONTHS + " months.");
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(String.join(System.lineSeparator(), errors));
        }
    }
}