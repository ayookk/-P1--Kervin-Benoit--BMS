package com.example.banking.security;

import java.time.Duration;
import java.time.Instant;

/** Who is logged in and when they were last active. Lives only in memory (doc AD-9). */
public final class SessionContext {

    private final long customerId;
    private final String username;
    private final Instant createdAt;
    private volatile Instant lastActivity;   // volatile: safe if a watchdog thread reads it later
    private volatile boolean valid = true;

    public SessionContext(long customerId, String username, Instant now) {
        this.customerId = customerId;
        this.username = username;
        this.createdAt = now;
        this.lastActivity = now;
    }

    /** Expired if logged out, or idle for at least the timeout. */
    public boolean isExpiredAt(Instant now, Duration idleTimeout) {
        return !valid || !now.isBefore(lastActivity.plus(idleTimeout));
    }

    /** Called after each successful action to reset the idle timer. */
    public void touch(Instant now) {
        this.lastActivity = now;
    }

    public void invalidate() {
        this.valid = false;
    }

    public boolean isValid() { return valid; }
    public long getCustomerId() { return customerId; }
    public String getUsername() { return username; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastActivity() { return lastActivity; }
}