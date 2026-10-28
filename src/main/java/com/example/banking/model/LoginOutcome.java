package com.example.banking.model;

/** Mirrors the login_audit.outcome CHECK constraint. */
public enum LoginOutcome {
    SUCCESS, FAILURE, LOCKED, TIMEOUT, LOGOUT
}