package com.example.banking.model;

import java.time.Duration;
import java.time.Instant;

public class Customer {

    private Long customerId;
    private String fullName;
    private String email;
    private String phone;
    private String username;
    private String passwordHash;
    private CustomerStatus status;
    private int failedLoginCount;
    private Instant lockedUntil;
    private Instant createdAt;
    private Instant updatedAt;

    /** Registering a NEW customer: the database generates id and timestamps. */
    public Customer(String fullName, String email, String phone, String username, String passwordHash) {
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.username = username;
        this.passwordHash = passwordHash;
        this.status = CustomerStatus.ACTIVE;
        this.failedLoginCount = 0;
    }

    /** Loading an EXISTING customer from the database. */
    public Customer(Long customerId, String fullName, String email, String phone, String username,
                    String passwordHash, CustomerStatus status, int failedLoginCount,
                    Instant lockedUntil, Instant createdAt, Instant updatedAt) {
        this.customerId = customerId;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.username = username;
        this.passwordHash = passwordHash;
        this.status = status;
        this.failedLoginCount = failedLoginCount;
        this.lockedUntil = lockedUntil;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // ---------- behaviour (business rules live on the model) ----------

    /** True if the customer cannot log in right now (permanently LOCKED or temporarily locked out). */
    public boolean isLockedAt(Instant now) {
        if (status == CustomerStatus.LOCKED) {
            return true;
        }
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public boolean isActive() {
        return status == CustomerStatus.ACTIVE;
    }

    /** Called after a wrong password. Locks the customer temporarily once maxFailures is reached. */
    public void registerFailure(Instant now, int maxFailures, Duration lockDuration) {
        failedLoginCount++;
        if (failedLoginCount >= maxFailures) {
            lockedUntil = now.plus(lockDuration);
            failedLoginCount = 0;   // fresh count once the lock expires
        }
    }

    /** Called after a successful login. */
    public void registerSuccess() {
        failedLoginCount = 0;
        lockedUntil = null;
    }

    // ---------- getters / limited setters ----------

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }

    public CustomerStatus getStatus() { return status; }
    public int getFailedLoginCount() { return failedLoginCount; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        // passwordHash deliberately excluded so it never appears in logs
        return "Customer{id=" + customerId + ", username='" + username + "', status=" + status + "}";
    }
}