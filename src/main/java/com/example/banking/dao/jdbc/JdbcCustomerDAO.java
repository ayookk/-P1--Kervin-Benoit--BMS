package com.example.banking.dao.jdbc;

import com.example.banking.dao.CustomerDAO;
import com.example.banking.exception.PersistenceException;
import com.example.banking.exception.ValidationException;
import com.example.banking.model.Customer;
import com.example.banking.model.CustomerStatus;
import com.example.banking.util.ConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;

public class JdbcCustomerDAO implements CustomerDAO {

    private static final String COLUMNS =
            "customer_id, full_name, email, phone, username, password_hash, status, "
          + "failed_login_count, locked_until, created_at, updated_at";

    private final ConnectionFactory connections;
    private final int queryTimeoutSeconds;

    public JdbcCustomerDAO(ConnectionFactory connections, int queryTimeoutSeconds) {
        this.connections = connections;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @Override
    public Customer insert(Customer c) {
        String sql = "INSERT INTO customers (full_name, email, phone, username, password_hash, status) "
                   + "VALUES (?, ?, ?, ?, ?, ?)";
        long newId;
        try (Connection conn = connections.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, c.getFullName());
            ps.setString(2, c.getEmail());
            ps.setString(3, c.getPhone());
            ps.setString(4, c.getUsername());
            ps.setString(5, c.getPasswordHash());
            ps.setString(6, c.getStatus().name());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                newId = keys.getLong(1);
            }
        } catch (SQLIntegrityConstraintViolationException e) {
            // UNIQUE constraint caught a duplicate (also covers two people registering at the same moment)
            throw new ValidationException("That username or email is already registered.");
        } catch (SQLException e) {
            throw new PersistenceException("Could not save customer", e);
        }
        return findById(newId).orElseThrow();
    }

    @Override
    public Optional<Customer> findById(long customerId) {
        return findOne("SELECT " + COLUMNS + " FROM customers WHERE customer_id = ?", ps -> ps.setLong(1, customerId));
    }

    @Override
    public Optional<Customer> findByUsername(String username) {
        return findOne("SELECT " + COLUMNS + " FROM customers WHERE username = ?", ps -> ps.setString(1, username));
    }

    @Override
    public boolean existsByUsername(String username) {
        return exists("SELECT 1 FROM customers WHERE username = ?", username);
    }

    @Override
    public boolean existsByEmail(String email) {
        return exists("SELECT 1 FROM customers WHERE email = ?", email);
    }

    @Override
    public void updatePasswordHash(long customerId, String newPasswordHash) {
        String sql = "UPDATE customers SET password_hash = ? WHERE customer_id = ?";
        try (Connection conn = connections.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, newPasswordHash);
            ps.setLong(2, customerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not update password", e);
        }
    }

    @Override
    public void updateLoginState(long customerId, int failedLoginCount, Instant lockedUntil) {
        String sql = "UPDATE customers SET failed_login_count = ?, locked_until = ? WHERE customer_id = ?";
        try (Connection conn = connections.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setInt(1, failedLoginCount);
            if (lockedUntil == null) {
                ps.setNull(2, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(2, Timestamp.from(lockedUntil));
            }
            ps.setLong(3, customerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not update login state", e);
        }
    }

    // ---------- helpers ----------

    /** Lets each query set its own parameters while sharing the open/close/map code. */
    @FunctionalInterface
    private interface ParamSetter {
        void set(PreparedStatement ps) throws SQLException;
    }

    private Optional<Customer> findOne(String sql, ParamSetter params) {
        try (Connection conn = connections.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            params.set(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not load customer", e);
        }
    }

    private boolean exists(String sql, String value) {
        try (Connection conn = connections.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not check customer", e);
        }
    }

    /** Turns one database row into a Customer object. */
    private static Customer mapRow(ResultSet rs) throws SQLException {
        return new Customer(
                rs.getLong("customer_id"),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("username"),
                rs.getString("password_hash"),
                CustomerStatus.valueOf(rs.getString("status")),
                rs.getInt("failed_login_count"),
                toInstant(rs.getTimestamp("locked_until")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}