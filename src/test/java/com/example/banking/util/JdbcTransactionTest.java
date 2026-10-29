package com.example.banking.util;

import static org.junit.jupiter.api.Assertions.*;

import com.example.banking.TestDatabase;
import com.example.banking.dao.AccountDAO;
import com.example.banking.dao.CustomerDAO;
import com.example.banking.dao.jdbc.JdbcAccountDAO;
import com.example.banking.dao.jdbc.JdbcCustomerDAO;
import com.example.banking.exception.PersistenceException;
import com.example.banking.model.Account;
import com.example.banking.model.Customer;
import com.example.banking.model.Money;
import com.example.banking.model.SavingsAccount;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcTransactionTest {

    private final ConnectionFactory connections = TestDatabase.connections();
    private final CustomerDAO customers = new JdbcCustomerDAO(connections, 10);
    private final AccountDAO accounts = new JdbcAccountDAO(connections, 10, Money.ZERO);
    private final JdbcTransaction tx = new JdbcTransaction(connections, 3);

    private long aliceId;
    private long bobId;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(connections);
        aliceId = customers.insert(new Customer("Alice", "alice@example.com", null, "alice", "hash")).getCustomerId();
        bobId = customers.insert(new Customer("Bob", "bob@example.com", null, "bob", "hash")).getCustomerId();
        accountId = accounts.insert(SavingsAccount.open(aliceId, "101000000001")).getAccountId();
    }

    private Money balance() {
        return accounts.findOwnedById(accountId, aliceId).orElseThrow().getBalance();
    }

    @Test
    void commitKeepsTheChange() {
        tx.execute(conn -> {
            accounts.updateBalance(conn, accountId, Money.of("100.00"));
            return null;
        });
        assertEquals(Money.of("100.00"), balance());
    }

    @Test
    void failureRollsBackEverythingAlreadyWritten() {
        assertThrows(IllegalStateException.class, () -> tx.execute(conn -> {
            accounts.updateBalance(conn, accountId, Money.of("100.00"));   // this write happens...
            throw new IllegalStateException("simulated crash after the update");
        }));
        assertEquals(Money.ZERO, balance());                               // ...and is undone
    }

    @Test
    void lockOwnedDoesNotFindAnotherCustomersAccount() {
        boolean found = tx.execute(conn -> accounts.lockOwned(conn, accountId, bobId).isPresent());
        assertFalse(found);
        Account mine = tx.execute(conn -> accounts.lockOwned(conn, accountId, aliceId).orElseThrow());
        assertEquals(accountId, mine.getAccountId());
    }

    @Test
    void deadlockAndLockTimeoutAreRecognisedAsRetryable() {
        assertTrue(JdbcTransaction.isTransient(new SQLException("deadlock", "40001", 1213)));
        assertTrue(JdbcTransaction.isTransient(
                new PersistenceException("wrapped", new SQLException("lock wait", "HY000", 1205))));
        assertFalse(JdbcTransaction.isTransient(new SQLException("duplicate key", "23000", 1062)));
    }
}