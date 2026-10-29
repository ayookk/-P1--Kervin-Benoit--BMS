package com.example.banking.dao.jdbc;

import com.example.banking.dao.TransactionAuditDAO;
import com.example.banking.exception.PersistenceException;
import com.example.banking.model.AuditOutcome;
import com.example.banking.model.TransactionType;
import com.example.banking.util.ConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class JdbcTransactionAuditDAO implements TransactionAuditDAO {

    private final ConnectionFactory connections;
    private final int queryTimeoutSeconds;

    public JdbcTransactionAuditDAO(ConnectionFactory connections, int queryTimeoutSeconds) {
        this.connections = connections;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @Override
    public void record(long customerId, String referenceNumber, TransactionType eventType,
                       AuditOutcome outcome, String failureCode) {
        String sql = "INSERT INTO transaction_audit (customer_id, reference_number, event_type, outcome, failure_code) "
                   + "VALUES (?, ?, ?, ?, ?)";
        // its own connection with auto-commit ON: independent of any money transaction
        try (Connection conn = connections.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setLong(1, customerId);
            ps.setString(2, referenceNumber);
            ps.setString(3, eventType.name());
            ps.setString(4, outcome.name());
            ps.setString(5, failureCode);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not write transaction audit", e);
        }
    }
}