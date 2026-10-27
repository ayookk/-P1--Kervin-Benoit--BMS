package com.example.banking.model;

import java.time.Instant;

public class SavingsAccount extends Account {

    private final Money minimumBalance;   // 0 by default (configurable)

    public SavingsAccount(Long accountId, Long customerId, String accountNumber, Money balance,
                          AccountStatus status, Instant createdAt, Money minimumBalance) {
        super(accountId, customerId, accountNumber, balance, status, createdAt);
        this.minimumBalance = minimumBalance;
    }

    /** Opens a brand-new savings account with a zero balance (P1-F03). */
    public static SavingsAccount open(Long customerId, String accountNumber) {
        return new SavingsAccount(null, customerId, accountNumber, Money.ZERO,
                AccountStatus.ACTIVE, null, Money.ZERO);
    }

    @Override public AccountType type()  { return AccountType.SAVINGS; }
    @Override public boolean canDebit()  { return isActive(); }
    @Override public boolean canCredit() { return isActive(); }

    /** After the withdrawal, the balance must still be at least the minimum balance. */
    @Override
    protected boolean hasFundsFor(Money amount) {
        return !getBalance().isLessThan(amount.add(minimumBalance));
    }

    public Money getMinimumBalance() { return minimumBalance; }
}