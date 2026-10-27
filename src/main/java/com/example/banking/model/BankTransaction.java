package com.example.banking.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One ledger row = one business transaction (design doc AD-6).
 * Immutable: every field is final and there are no setters.
 * Created only through the factory methods below, so invalid shapes cannot exist.
 */
public final class BankTransaction {

    public enum Direction { CREDIT, DEBIT }

    private final Long transactionId;          // null until saved
    private final String referenceNumber;
    private final Long sourceAccountId;        // null for DEPOSIT
    private final Long destinationAccountId;   // null for WITHDRAWAL
    private final TransactionType type;
    private final Money amount;
    private final TransactionStatus status;
    private final String description;
    private final Money sourceBalanceAfter;
    private final Money destinationBalanceAfter;
    private final Instant createdAt;           // null until saved

    private BankTransaction(Long transactionId, String referenceNumber, Long sourceAccountId,
                            Long destinationAccountId, TransactionType type, Money amount,
                            TransactionStatus status, String description, Money sourceBalanceAfter,
                            Money destinationBalanceAfter, Instant createdAt) {
        this.referenceNumber = Objects.requireNonNull(referenceNumber, "referenceNumber");
        this.type = Objects.requireNonNull(type, "type");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.status = Objects.requireNonNull(status, "status");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Transaction amount must be greater than zero");
        }
        requireValidShape(type, sourceAccountId, destinationAccountId);

        this.transactionId = transactionId;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.description = description;
        this.sourceBalanceAfter = sourceBalanceAfter;
        this.destinationBalanceAfter = destinationBalanceAfter;
        this.createdAt = createdAt;
    }

    /** Java mirror of the database CHECK constraint chk_txn_shape. */
    private static void requireValidShape(TransactionType type, Long src, Long dst) {
        boolean ok = switch (type) {
            case DEPOSIT        -> src == null && dst != null;
            case WITHDRAWAL     -> src != null && dst == null;
            case TRANSFER, LOAN_REPAYMENT -> src != null && dst != null && !src.equals(dst);
        };
        if (!ok) {
            throw new IllegalArgumentException("Invalid source/destination for " + type);
        }
    }

    // ---------- factory methods: the only way to create a new ledger entry ----------

    public static BankTransaction deposit(String ref, Long accountId, Money amount, Money balanceAfter) {
        return new BankTransaction(null, ref, null, accountId, TransactionType.DEPOSIT, amount,
                TransactionStatus.SUCCESS, "Deposit", null, balanceAfter, null);
    }

    public static BankTransaction withdrawal(String ref, Long accountId, Money amount, Money balanceAfter) {
        return new BankTransaction(null, ref, accountId, null, TransactionType.WITHDRAWAL, amount,
                TransactionStatus.SUCCESS, "Withdrawal", balanceAfter, null, null);
    }

    public static BankTransaction transfer(String ref, Long sourceId, Long destinationId, Money amount,
                                           Money sourceAfter, Money destinationAfter) {
        return new BankTransaction(null, ref, sourceId, destinationId, TransactionType.TRANSFER, amount,
                TransactionStatus.SUCCESS, "Transfer", sourceAfter, destinationAfter, null);
    }

    public static BankTransaction loanRepayment(String ref, Long fundingId, Long loanId, Money amount,
                                                Money fundingAfter, Money loanAfter) {
        return new BankTransaction(null, ref, fundingId, loanId, TransactionType.LOAN_REPAYMENT, amount,
                TransactionStatus.SUCCESS, "Loan repayment", fundingAfter, loanAfter, null);
    }

    /** Rebuilds a row read from the database (used only by the DAO). */
    public static BankTransaction restore(Long transactionId, String ref, Long sourceId, Long destinationId,
                                          TransactionType type, Money amount, TransactionStatus status,
                                          String description, Money sourceAfter, Money destinationAfter,
                                          Instant createdAt) {
        return new BankTransaction(transactionId, ref, sourceId, destinationId, type, amount, status,
                description, sourceAfter, destinationAfter, createdAt);
    }

    /** Returns a copy carrying the database-generated id and timestamp (the original is unchanged). */
    public BankTransaction withId(Long newId, Instant newCreatedAt) {
        return new BankTransaction(newId, referenceNumber, sourceAccountId, destinationAccountId, type,
                amount, status, description, sourceBalanceAfter, destinationBalanceAfter, newCreatedAt);
    }

    /** Money coming in or going out, from the point of view of the given account (for history). */
    public Direction directionFor(Long accountId) {
        return accountId.equals(destinationAccountId) ? Direction.CREDIT : Direction.DEBIT;
    }

    // ---------- getters only ----------

    public Long getTransactionId() { return transactionId; }
    public String getReferenceNumber() { return referenceNumber; }
    public Long getSourceAccountId() { return sourceAccountId; }
    public Long getDestinationAccountId() { return destinationAccountId; }
    public TransactionType getType() { return type; }
    public Money getAmount() { return amount; }
    public TransactionStatus getStatus() { return status; }
    public String getDescription() { return description; }
    public Money getSourceBalanceAfter() { return sourceBalanceAfter; }
    public Money getDestinationBalanceAfter() { return destinationBalanceAfter; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return createdAt + " | " + type + " | $" + amount + " | " + status + " | Ref: " + referenceNumber;
    }
}