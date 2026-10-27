package com.example.banking.model;

import com.example.banking.exception.AccountNotEligibleException;
import com.example.banking.exception.InsufficientFundsException;
import com.example.banking.exception.ValidationException;
import java.time.Instant;
import java.util.Objects;

/**
 * Base class for all account types. Holds shared state and the shared debit/credit rules.
 * There is deliberately NO public balance setter: balances change only through debit/credit.
 */
public abstract class Account {

    private Long accountId;
    private final Long customerId;
    private final String accountNumber;
    private Money balance;
    private AccountStatus status;
    private final Instant createdAt;

    protected Account(Long accountId, Long customerId, String accountNumber,
                      Money balance, AccountStatus status, Instant createdAt) {
        this.accountId = accountId;
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.accountNumber = Objects.requireNonNull(accountNumber, "accountNumber");
        this.balance = Objects.requireNonNull(balance, "balance");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = createdAt;
    }

    // ---------- what each subclass must decide ----------

    public abstract AccountType type();
    public abstract boolean canDebit();
    public abstract boolean canCredit();

    /** Default funds rule: balance must cover the amount. Subclasses may tighten it. */
    protected boolean hasFundsFor(Money amount) {
        return !balance.isLessThan(amount);
    }

    // ---------- shared behaviour ----------

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    /** Removes money. Enforces eligibility and funds; returns the new balance. */
    public Money debit(Money amount) {
        requirePositive(amount);
        if (!canDebit()) {
            throw new AccountNotEligibleException("This account cannot be debited.");
        }
        if (!hasFundsFor(amount)) {
            throw new InsufficientFundsException();
        }
        balance = balance.subtract(amount);
        return balance;
    }

    /** Adds money. Enforces eligibility; returns the new balance. */
    public Money credit(Money amount) {
        requirePositive(amount);
        if (!canCredit()) {
            throw new AccountNotEligibleException("This account cannot receive funds.");
        }
        balance = balance.add(amount);
        return balance;
    }

    public void freeze() {
        if (status == AccountStatus.CLOSED) {
            throw new AccountNotEligibleException("A closed account cannot be frozen.");
        }
        status = AccountStatus.FROZEN;
    }

    public void unfreeze() {
        if (status != AccountStatus.FROZEN) {
            throw new AccountNotEligibleException("Only a frozen account can be unfrozen.");
        }
        status = AccountStatus.ACTIVE;
    }

    public void close() {
        if (!balance.isZero()) {
            throw new AccountNotEligibleException("Account balance must be zero to close it.");
        }
        status = AccountStatus.CLOSED;
    }

    // ---------- helpers for subclasses only ----------

    protected void setBalanceInternal(Money newBalance) {
        this.balance = Objects.requireNonNull(newBalance);
    }

    protected void setStatusInternal(AccountStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus);
    }

    protected static void requirePositive(Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw new ValidationException("Amount must be greater than zero.");
        }
    }

    // ---------- getters ----------

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }  // set once after INSERT

    public Long getCustomerId() { return customerId; }
    public String getAccountNumber() { return accountNumber; }
    public Money getBalance() { return balance; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    /** Last 4 digits only: safe for logs (design doc section 24). */
    public String maskedNumber() {
        int n = accountNumber.length();
        return "****" + accountNumber.substring(Math.max(0, n - 4));
    }

    @Override
    public String toString() {
        return type() + " " + maskedNumber() + " | Balance: $" + balance + " | " + status;
    }
}