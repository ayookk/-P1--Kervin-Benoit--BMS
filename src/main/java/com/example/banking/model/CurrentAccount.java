package com.example.banking.model;

import java.time.Instant;

public class CurrentAccount extends Account {

    /** Overdraft is not allowed in the baseline (design doc section 5). */
    public static final Money OVERDRAFT_LIMIT = Money.ZERO;

    public CurrentAccount(Long accountId, Long customerId, String accountNumber, Money balance,
                          AccountStatus status, Instant createdAt) {
        super(accountId, customerId, accountNumber, balance, status, createdAt);
    }

    public static CurrentAccount open(Long customerId, String accountNumber) {
        return new CurrentAccount(null, customerId, accountNumber, Money.ZERO,
                AccountStatus.ACTIVE, null);
    }

    @Override public AccountType type()  { return AccountType.CURRENT; }
    @Override public boolean canDebit()  { return isActive(); }
    @Override public boolean canCredit() { return isActive(); }
}