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
    private static final Money MIN_DEPOSIT = Money.of("100.00");
    private static final int MAX_DEPOSIT_TERM_MONTHS = 120;

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

    public void validateDepositTerms(Money principal, int termMonths, BigDecimal annualRate) {
        List<String> errors = new ArrayList<>();

        if (principal == null || principal.isLessThan(MIN_DEPOSIT)) {
            errors.add("A fixed-term deposit must be at least $" + MIN_DEPOSIT + ".");
        } else if (principal.isGreaterThan(maxAmount)) {
            errors.add("Deposit amount cannot exceed $" + maxAmount + ".");
        }
        if (termMonths < 1 || termMonths > MAX_DEPOSIT_TERM_MONTHS) {
            errors.add("Term must be 1 to " + MAX_DEPOSIT_TERM_MONTHS + " months.");
        }
        if (annualRate == null || annualRate.signum() < 0 || annualRate.compareTo(MAX_RATE) > 0) {
            errors.add("Interest rate must be between 0 and 100 percent.");
        } else if (annualRate.stripTrailingZeros().scale() > 2) {
            errors.add("Interest rate can have at most 2 decimal places.");
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(String.join(System.lineSeparator(), errors));
        }
    }
}