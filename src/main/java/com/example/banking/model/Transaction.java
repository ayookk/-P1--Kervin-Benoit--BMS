package com.example.banking.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Transaction {

    private Long transactionId;
    private String referenceNumber;
    private Long sourceAccountId;        // null for deposits
    private Long destinationAccountId;   // null for withdrawals
    private TransactionType type;
    private BigDecimal amount;
    private TransactionStatus status;
    private BigDecimal sourceBalanceAfter;
    private BigDecimal destinationBalanceAfter;
    private String description;
    private LocalDateTime createdAt;

    public Transaction(Long transactionId, String referenceNumber, Long sourceAccountId,
                       Long destinationAccountId, TransactionType type, BigDecimal amount,
                       TransactionStatus status, BigDecimal sourceBalanceAfter,
                       BigDecimal destinationBalanceAfter, String description,
                       LocalDateTime createdAt) {
        this.transactionId = transactionId;
        this.referenceNumber = referenceNumber;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.type = type;
        this.amount = amount;
        this.status = status;
        this.sourceBalanceAfter = sourceBalanceAfter;
        this.destinationBalanceAfter = destinationBalanceAfter;
        this.description = description;
        this.createdAt = createdAt;
    }

    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }

    public String getReferenceNumber() { return referenceNumber; }
    public Long getSourceAccountId() { return sourceAccountId; }
    public Long getDestinationAccountId() { return destinationAccountId; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public TransactionStatus getStatus() { return status; }
    public BigDecimal getSourceBalanceAfter() { return sourceBalanceAfter; }
    public BigDecimal getDestinationBalanceAfter() { return destinationBalanceAfter; }
    public String getDescription() { return description; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return createdAt + " | " + type + " | $" + amount + " | " + status + " | Ref: " + referenceNumber;
    }
}