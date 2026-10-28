package com.example.banking.dao.jdbc;

import com.example.banking.dao.AccountDAO;
import com.example.banking.exception.PersistenceException;
import com.example.banking.model.Account;
import com.example.banking.model.AccountStatus;
import com.example.banking.model.AccountType;
import com.example.banking.model.CurrentAccount;
import com.example.banking.model.DepositAccount;
import com.example.banking.model.LoanAccount;
import com.example.banking.model.Money;
import com.example.banking.model.SavingsAccount;
import com.example.banking.util.ConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcAccountDAO implements AccountDAO {

    /** One query shape for every lookup: the account plus its optional deposit/loan details. */
    private static final String SELECT =
            "SELECT a.account_id, a.customer_id, a.account_number, a.account_type, a.balance, a.status, a.created_at, "
          + "       d.principal AS dep_principal, d.term_months, d.interest_rate, d.maturity_date, "
          + "       l.principal_amount, l.annual_rate, l.tenure_months, l.start_date "
          + "FROM accounts a "
          + "LEFT JOIN deposit_details d ON d.account_id = a.account_id "
          + "LEFT JOIN loan_details l ON l.account_id = a.account_id ";

    private final ConnectionFactory connections;
    private final int queryTimeoutSeconds;
    private final Money savingsMinimumBalance;

    public JdbcAccountDAO(ConnectionFactory connections, int queryTimeoutSeconds, Money savingsMinimumBalance) {
        this.connections = connections;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.savingsMinimumBalance = savingsMinimumBalance;
    }

    // ---------- insert ----------

    @Override
    public Account insert(Account account) {
        try (Connection conn = connections.getConnection()) {
            conn.setAutoCommit(false);              // accounts row + details row: both or neither
            try {
                insert(conn, account);
                conn.commit();
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not open account", e);
        }
        return findOwnedById(account.getAccountId(), account.getCustomerId()).orElseThrow();
    }

    @Override
    public Account insert(Connection conn, Account account) {
        String sql = "INSERT INTO accounts (customer_id, account_number, account_type, balance, status) "
                   + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setLong(1, account.getCustomerId());
            ps.setString(2, account.getAccountNumber());
            ps.setString(3, account.type().name());
            ps.setBigDecimal(4, account.getBalance().toBigDecimal());
            ps.setString(5, account.getStatus().name());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                account.setAccountId(keys.getLong(1));
            }

            // type-specific details (pattern matching instanceof, Java 16+)
            if (account instanceof DepositAccount dep) {
                insertDepositDetails(conn, dep);
            } else if (account instanceof LoanAccount loan) {
                insertLoanDetails(conn, loan);
            }
            return account;
        } catch (SQLException e) {
            throw new PersistenceException("Could not insert account", e);
        }
    }

    private void insertDepositDetails(Connection conn, DepositAccount dep) throws SQLException {
        String sql = "INSERT INTO deposit_details (account_id, principal, term_months, interest_rate, maturity_date) "
                   + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setLong(1, dep.getAccountId());
            ps.setBigDecimal(2, dep.getPrincipal().toBigDecimal());
            ps.setInt(3, dep.getTermMonths());
            ps.setBigDecimal(4, dep.getInterestRate());
            ps.setObject(5, dep.getMaturityDate());
            ps.executeUpdate();
        }
    }

    private void insertLoanDetails(Connection conn, LoanAccount loan) throws SQLException {
        String sql = "INSERT INTO loan_details (account_id, principal_amount, annual_rate, tenure_months, start_date) "
                   + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setLong(1, loan.getAccountId());
            ps.setBigDecimal(2, loan.getPrincipal().toBigDecimal());
            ps.setBigDecimal(3, loan.getAnnualRate());
            ps.setInt(4, loan.getTenureMonths());
            ps.setObject(5, loan.getStartDate());
            ps.executeUpdate();
        }
    }

    // ---------- queries ----------

    @Override
    public Optional<Account> findOwnedById(long accountId, long customerId) {
        String sql = SELECT + "WHERE a.account_id = ? AND a.customer_id = ?";
        try (Connection conn = connections.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setLong(1, accountId);
            ps.setLong(2, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not load account", e);
        }
    }

    @Override
    public Optional<Account> findByNumber(String accountNumber) {
        String sql = SELECT + "WHERE a.account_number = ?";
        try (Connection conn = connections.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, accountNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not load account", e);
        }
    }

    @Override
    public List<Account> listByCustomer(long customerId) {
        String sql = SELECT + "WHERE a.customer_id = ? ORDER BY a.account_id";
        List<Account> result = new ArrayList<>();
        try (Connection conn = connections.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not list accounts", e);
        }
        return result;
    }

    @Override
    public void updateStatus(long accountId, AccountStatus status) {
        String sql = "UPDATE accounts SET status = ? WHERE account_id = ?";
        try (Connection conn = connections.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, status.name());
            ps.setLong(2, accountId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not update account status", e);
        }
    }

    // ---------- mapping ----------

    /** Builds the right subclass for the row's account_type (polymorphism starts here). */
    private Account mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("account_id");
        long customerId = rs.getLong("customer_id");
        String number = rs.getString("account_number");
        AccountType type = AccountType.valueOf(rs.getString("account_type"));
        Money balance = Money.of(rs.getBigDecimal("balance"));
        AccountStatus status = AccountStatus.valueOf(rs.getString("status"));
        Instant createdAt = toInstant(rs.getTimestamp("created_at"));

        return switch (type) {
            case SAVINGS -> new SavingsAccount(id, customerId, number, balance, status, createdAt,
                    savingsMinimumBalance);
            case CURRENT -> new CurrentAccount(id, customerId, number, balance, status, createdAt);
            case DEPOSIT -> new DepositAccount(id, customerId, number, balance, status, createdAt,
                    Money.of(rs.getBigDecimal("dep_principal")), rs.getInt("term_months"),
                    rs.getBigDecimal("interest_rate"), rs.getObject("maturity_date", LocalDate.class));
            case LOAN -> new LoanAccount(id, customerId, number, balance, status, createdAt,
                    Money.of(rs.getBigDecimal("principal_amount")), rs.getBigDecimal("annual_rate"),
                    rs.getInt("tenure_months"), rs.getObject("start_date", LocalDate.class));
        };
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}