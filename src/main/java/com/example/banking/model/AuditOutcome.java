package com.example.banking.model;

/** Outcome of a money attempt in transaction_audit (doc section 17.1). */
public enum AuditOutcome {
    SUCCESS, REJECTED, FAILED, ROLLED_BACK
}