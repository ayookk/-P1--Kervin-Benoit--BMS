package com.example.banking.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Account {

    private Long accountId;
    private Long customerId;
    private String accountNumber;
    private AccountType accountType;
    private BigDecimal balance;
    private AccountStatus status;
    private LocalDateTime createdAt;

    // For opening a NEW account: balance always starts at zero (P1-F03)
    public Account(Long customerId, String accountNumber, AccountType accountType) {
        this.customerId = customerId;
        this.accountNumber = accountNumber;
        this.accountType = accountType;
        this.balance = BigDecimal.ZERO;
        this.status = AccountStatus.ACTIVE;
    }

    // For loading an EXISTING account from the database
    public Account(Long accountId, Long customerId, String accountNumber, AccountType accountType,
                   BigDecimal balance, AccountStatus status, LocalDateTime createdAt) {
        this.accountId = accountId;
        this.customerId = customerId;
        this.accountNumber = accountNumber;
        this.accountType = accountType;
        this.balance = balance;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public Long getCustomerId() { return customerId; }
    public String getAccountNumber() { return accountNumber; }
    public AccountType getAccountType() { return accountType; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public AccountStatus getStatus() { return status; }
    public void setStatus(AccountStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public boolean isActive() { return status == AccountStatus.ACTIVE; }

    @Override
    public String toString() {
        return accountType + " " + accountNumber + " | Balance: $" + balance + " | " + status;
    }
}