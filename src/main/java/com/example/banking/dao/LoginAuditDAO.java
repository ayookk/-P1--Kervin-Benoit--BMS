package com.example.banking.dao;

import com.example.banking.model.LoginOutcome;

/** Append-only: records login attempts. Never updates or deletes (doc INV-11). */
public interface LoginAuditDAO {

    /** customerId may be null when the username doesn't exist. */
    void record(Long customerId, String usernameAttempted, LoginOutcome outcome);
}