package com.example.banking.dao;

import static org.junit.jupiter.api.Assertions.*;

import com.example.banking.TestDatabase;
import com.example.banking.dao.jdbc.JdbcAccountDAO;
import com.example.banking.dao.jdbc.JdbcCustomerDAO;
import com.example.banking.dao.jdbc.JdbcTransactionAuditDAO;
import com.example.banking.dao.jdbc.JdbcTransactionDAO;
import com.example.banking.model.AuditOutcome;
import com.example.banking.model.BankTransaction;
import com.example.banking.model.Customer;
import com.example.banking.model.Money;
import com.example.banking.model.SavingsAccount;
import com.example.banking.model.TransactionType;
import com.example.banking.util.ConnectionFactory;
import com.example.banking.util.JdbcTransaction;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcTransactionDAOTest {

    private final ConnectionFactory connections = TestDatabase.connections();
    private final CustomerDAO customers = new JdbcCustomerDAO(connections, 10);
    private final AccountDAO accounts = new JdbcAccountDAO(connections, 10, Money.ZERO);
    private final TransactionDAO ledger = new JdbcTransactionDAO(connections, 10);
    private final TransactionAuditDAO audit = new JdbcTransactionAuditDAO(connections, 10);
    private final JdbcTransaction tx = new JdbcTransaction(connections, 3);

    private long aliceId;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(connections);
        aliceId = customers.insert(new Customer("Alice", "alice@example.com", null, "alice", "hash")).getCustomerId();
        accountId = accounts.insert(SavingsAccount.open(aliceId, "101000000001")).getAccountId();
    }

    private BankTransaction deposit(String ref, String amount) {
        return tx.execute(conn -> ledger.insert(conn,
                BankTransaction.deposit(ref, accountId, Money.of(amount), Money.of(amount))));
    }

    @Test
    void insertedRowCanBeFoundByReference() {
        BankTransaction saved = deposit("TXN-TEST-1", "50.00");
        assertNotNull(saved.getTransactionId());
        assertNotNull(saved.getCreatedAt());

        BankTransaction found = ledger.findByReference("TXN-TEST-1").orElseThrow();
        assertEquals(TransactionType.DEPOSIT, found.getType());
        assertEquals(Money.of("50.00"), found.getAmount());
        assertNull(found.getSourceAccountId());                 // deposits have no source
        assertEquals(accountId, found.getDestinationAccountId());
        assertEquals(Money.of("50.00"), found.getDestinationBalanceAfter());
    }

    @Test
    void historyIsNewestFirstAndPagesCorrectly() {
        deposit("TXN-TEST-A", "10.00");
        deposit("TXN-TEST-B", "20.00");
        deposit("TXN-TEST-C", "30.00");

        List<BankTransaction> first = ledger.pageByAccount(accountId, 2, 0);
        assertEquals(List.of("TXN-TEST-C", "TXN-TEST-B"),
                first.stream().map(BankTransaction::getReferenceNumber).toList());

        List<BankTransaction> second = ledger.pageByAccount(accountId, 2, 2);
        assertEquals(List.of("TXN-TEST-A"),
                second.stream().map(BankTransaction::getReferenceNumber).toList());

        assertEquals(3, ledger.countByAccount(accountId));
    }

    @Test
    void auditRecordsARejectedAttempt() throws Exception {
        audit.record(aliceId, null, TransactionType.WITHDRAWAL, AuditOutcome.REJECTED, "INSUFFICIENT_FUNDS");
        try (Connection c = connections.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM transaction_audit WHERE failure_code = 'INSUFFICIENT_FUNDS'")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }
}