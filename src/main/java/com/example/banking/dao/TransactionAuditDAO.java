package com.example.banking.dao;

import com.example.banking.model.AuditOutcome;
import com.example.banking.model.TransactionType;

/**
 * Append-only record of every money ATTEMPT, including failures (doc AD-7).
 * Written in its own short transaction, OUTSIDE the money transaction,
 * so a failure is still recorded after the money work rolls back.
 */
public interface TransactionAuditDAO {

    /** failureCode e.g. "INSUFFICIENT_FUNDS"; null on success. Never raw SQL or stack traces. */
    void record(long customerId, String referenceNumber, TransactionType eventType,
                AuditOutcome outcome, String failureCode);
}