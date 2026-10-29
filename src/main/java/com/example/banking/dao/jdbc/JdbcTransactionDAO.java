package com.example.banking.dao.jdbc;

import com.example.banking.dao.TransactionDAO;
import com.example.banking.exception.PersistenceException;
import com.example.banking.model.BankTransaction;
import com.example.banking.model.Money;
import com.example.banking.model.TransactionStatus;
import com.example.banking.model.TransactionType;
import com.example.banking.util.ConnectionFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcTransactionDAO implements TransactionDAO {

    private static final String COLUMNS =
            "transaction_id, reference_number, source_account_id, destination_account_id, transaction_type, "
          + "amount, status, description, source_balance_after, dest_balance_after, created_at";

    private final ConnectionFactory connections;
    private final int queryTimeoutSeconds;

    public JdbcTransactionDAO(ConnectionFactory connections, int queryTimeoutSeconds) {
        this.connections = connections;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @Override
    public BankTransaction insert(Connection conn, BankTransaction tx) {
        String sql = "INSERT INTO transactions (reference_number, source_account_id, destination_account_id, "
                   + "transaction_type, amount, status, description, source_balance_after, dest_balance_after) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, tx.getReferenceNumber());
            setNullableLong(ps, 2, tx.getSourceAccountId());
            setNullableLong(ps, 3, tx.getDestinationAccountId());
            ps.setString(4, tx.getType().name());
            ps.setBigDecimal(5, tx.getAmount().toBigDecimal());
            ps.setString(6, tx.getStatus().name());
            ps.setString(7, tx.getDescription());
            setNullableMoney(ps, 8, tx.getSourceBalanceAfter());
            setNullableMoney(ps, 9, tx.getDestinationBalanceAfter());
            ps.executeUpdate();

            long id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
            return tx.withId(id, readCreatedAt(conn, id));
        } catch (SQLException e) {
            throw new PersistenceException("Could not write ledger entry", e);
        }
    }

    @Override
    public Optional<BankTransaction> findByReference(String referenceNumber) {
        String sql = "SELECT " + COLUMNS + " FROM transactions WHERE reference_number = ?";
        try (Connection conn = connections.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, referenceNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not load transaction", e);
        }
    }

    @Override
    public List<BankTransaction> pageByAccount(long accountId, int limit, int offset) {
        String sql = "SELECT " + COLUMNS + " FROM transactions "
                   + "WHERE source_account_id = ? OR destination_account_id = ? "
                   + "ORDER BY created_at DESC, transaction_id DESC "    // id breaks ties: stable pages
                   + "LIMIT ? OFFSET ?";
        List<BankTransaction> page = new ArrayList<>();
        try (Connection conn = connections.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setLong(1, accountId);
            ps.setLong(2, accountId);
            ps.setInt(3, limit);
            ps.setInt(4, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    page.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not load history", e);
        }
        return page;
    }

    @Override
    public int countByAccount(long accountId) {
        String sql = "SELECT COUNT(*) FROM transactions WHERE source_account_id = ? OR destination_account_id = ?";
        try (Connection conn = connections.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setLong(1, accountId);
            ps.setLong(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not count history", e);
        }
    }

    // ---------- helpers ----------

    private Instant readCreatedAt(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT created_at FROM transactions WHERE transaction_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toInstant();
            }
        }
    }

    private static BankTransaction mapRow(ResultSet rs) throws SQLException {
        return BankTransaction.restore(
                rs.getLong("transaction_id"),
                rs.getString("reference_number"),
                rs.getObject("source_account_id", Long.class),
                rs.getObject("destination_account_id", Long.class),
                TransactionType.valueOf(rs.getString("transaction_type")),
                Money.of(rs.getBigDecimal("amount")),
                TransactionStatus.valueOf(rs.getString("status")),
                rs.getString("description"),
                nullableMoney(rs.getBigDecimal("source_balance_after")),
                nullableMoney(rs.getBigDecimal("dest_balance_after")),
                rs.getTimestamp("created_at").toInstant());
    }

    private static Money nullableMoney(BigDecimal value) {
        return value == null ? null : Money.of(value);
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setNullableMoney(PreparedStatement ps, int index, Money value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DECIMAL);
        } else {
            ps.setBigDecimal(index, value.toBigDecimal());
        }
    }
}