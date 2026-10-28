package com.example.banking.dao.jdbc;

import com.example.banking.dao.LoginAuditDAO;
import com.example.banking.exception.PersistenceException;
import com.example.banking.model.LoginOutcome;
import com.example.banking.util.ConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

public class JdbcLoginAuditDAO implements LoginAuditDAO {

    private static final int MAX_USERNAME_LENGTH = 80;   // matches the column size

    private final ConnectionFactory connections;
    private final int queryTimeoutSeconds;

    public JdbcLoginAuditDAO(ConnectionFactory connections, int queryTimeoutSeconds) {
        this.connections = connections;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @Override
    public void record(Long customerId, String usernameAttempted, LoginOutcome outcome) {
        String sql = "INSERT INTO login_audit (customer_id, username_attempted, outcome) VALUES (?, ?, ?)";
        String name = usernameAttempted == null ? "" : usernameAttempted;
        if (name.length() > MAX_USERNAME_LENGTH) {
            name = name.substring(0, MAX_USERNAME_LENGTH);
        }
        try (Connection conn = connections.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            if (customerId == null) {
                ps.setNull(1, Types.BIGINT);
            } else {
                ps.setLong(1, customerId);
            }
            ps.setString(2, name);
            ps.setString(3, outcome.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not write login audit", e);
        }
    }
}