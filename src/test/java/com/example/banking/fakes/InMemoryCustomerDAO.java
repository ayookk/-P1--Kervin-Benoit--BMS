package com.example.banking.fakes;

import com.example.banking.dao.CustomerDAO;
import com.example.banking.exception.ValidationException;
import com.example.banking.model.Customer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Test double: behaves like JdbcCustomerDAO but stores customers in a Map. */
public class InMemoryCustomerDAO implements CustomerDAO {

    private final Map<Long, Customer> rows = new LinkedHashMap<>();
    private long nextId = 1;

    @Override
    public Customer insert(Customer c) {
        if (existsByUsername(c.getUsername()) || existsByEmail(c.getEmail())) {
            throw new ValidationException("That username or email is already registered.");
        }
        long id = nextId++;
        Instant now = Instant.now();
        Customer stored = new Customer(id, c.getFullName(), c.getEmail(), c.getPhone(), c.getUsername(),
                c.getPasswordHash(), c.getStatus(), 0, null, now, now);
        rows.put(id, stored);
        return copy(stored, stored.getPasswordHash(), 0, null);
    }

    @Override
    public Optional<Customer> findById(long customerId) {
        return Optional.ofNullable(rows.get(customerId))
                .map(c -> copy(c, c.getPasswordHash(), c.getFailedLoginCount(), c.getLockedUntil()));
    }

    @Override
    public Optional<Customer> findByUsername(String username) {
        // MySQL's default collation is case-insensitive, so the fake is too
        return rows.values().stream()
                .filter(c -> c.getUsername().equalsIgnoreCase(username))
                .findFirst()
                .map(c -> copy(c, c.getPasswordHash(), c.getFailedLoginCount(), c.getLockedUntil()));
    }

    @Override
    public boolean existsByUsername(String username) {
        return rows.values().stream().anyMatch(c -> c.getUsername().equalsIgnoreCase(username));
    }

    @Override
    public boolean existsByEmail(String email) {
        return rows.values().stream().anyMatch(c -> c.getEmail().equalsIgnoreCase(email));
    }

    @Override
    public void updatePasswordHash(long customerId, String newPasswordHash) {
        Customer c = rows.get(customerId);
        rows.put(customerId, copy(c, newPasswordHash, c.getFailedLoginCount(), c.getLockedUntil()));
    }

    @Override
    public void updateLoginState(long customerId, int failedLoginCount, Instant lockedUntil) {
        Customer c = rows.get(customerId);
        rows.put(customerId, copy(c, c.getPasswordHash(), failedLoginCount, lockedUntil));
    }

    /** Returns copies so the service can't change "stored" data without calling an update method. */
    private static Customer copy(Customer c, String hash, int failed, Instant lockedUntil) {
        return new Customer(c.getCustomerId(), c.getFullName(), c.getEmail(), c.getPhone(), c.getUsername(),
                hash, c.getStatus(), failed, lockedUntil, c.getCreatedAt(), c.getUpdatedAt());
    }
}