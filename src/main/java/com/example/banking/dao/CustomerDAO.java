package com.example.banking.dao;

import com.example.banking.model.Customer;
import java.time.Instant;
import java.util.Optional;

/** Persistence operations for customers. All implementations must use PreparedStatement. */
public interface CustomerDAO {

    /** Saves a new customer and returns it as stored (with id and timestamps). */
    Customer insert(Customer customer);

    Optional<Customer> findById(long customerId);

    Optional<Customer> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    void updatePasswordHash(long customerId, String newPasswordHash);

    /** Saves the lockout state after a login attempt. */
    void updateLoginState(long customerId, int failedLoginCount, Instant lockedUntil);
}