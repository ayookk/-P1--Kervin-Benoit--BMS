package com.example.banking.dao;

import static org.junit.jupiter.api.Assertions.*;

import com.example.banking.TestDatabase;
import com.example.banking.dao.jdbc.JdbcAccountDAO;
import com.example.banking.dao.jdbc.JdbcCustomerDAO;
import com.example.banking.model.Account;
import com.example.banking.model.AccountStatus;
import com.example.banking.model.AccountType;
import com.example.banking.model.Customer;
import com.example.banking.model.DepositAccount;
import com.example.banking.model.LoanAccount;
import com.example.banking.model.Money;
import com.example.banking.model.SavingsAccount;
import com.example.banking.util.ConnectionFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcAccountDAOTest {

    private final ConnectionFactory connections = TestDatabase.connections();
    private final CustomerDAO customers = new JdbcCustomerDAO(connections, 10);
    private final AccountDAO accounts = new JdbcAccountDAO(connections, 10, Money.ZERO);

    private long aliceId;
    private long bobId;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(connections);
        aliceId = customers.insert(new Customer("Alice", "alice@example.com", null, "alice", "hash")).getCustomerId();
        bobId = customers.insert(new Customer("Bob", "bob@example.com", null, "bob", "hash")).getCustomerId();
    }

    @Test
    void savingsAccountStartsAtZero() {
        Account saved = accounts.insert(SavingsAccount.open(aliceId, "101000000001"));
        Account found = accounts.findOwnedById(saved.getAccountId(), aliceId).orElseThrow();
        assertInstanceOf(SavingsAccount.class, found);
        assertEquals(Money.ZERO, found.getBalance());
        assertEquals(AccountStatus.ACTIVE, found.getStatus());
        assertNotNull(found.getCreatedAt());
    }

    @Test
    void loanAccountStoresItsDetails() {
        LoanAccount loan = LoanAccount.open(aliceId, "104000000001", Money.of("5000.00"),
                new BigDecimal("6.50"), 12, LocalDate.of(2026, 10, 28));
        Account saved = accounts.insert(loan);

        LoanAccount found = (LoanAccount) accounts.findOwnedById(saved.getAccountId(), aliceId).orElseThrow();
        assertEquals(Money.of("5000.00"), found.getBalance());       // amount owed
        assertEquals(Money.of("5000.00"), found.getPrincipal());
        assertEquals(12, found.getTenureMonths());
        assertEquals(LocalDate.of(2026, 10, 28), found.getStartDate());
    }

    @Test
    void depositAccountStoresMaturityDate() {
        DepositAccount dep = DepositAccount.open(aliceId, "103000000001", Money.of("1000.00"),
                6, new BigDecimal("3.00"), LocalDate.of(2026, 10, 28));
        Account saved = accounts.insert(dep);

        DepositAccount found = (DepositAccount) accounts.findOwnedById(saved.getAccountId(), aliceId).orElseThrow();
        assertEquals(LocalDate.of(2027, 4, 28), found.getMaturityDate());
        assertEquals(6, found.getTermMonths());
    }

    @Test
    void T06_anotherCustomerCannotLoadMyAccount() {
        Account mine = accounts.insert(SavingsAccount.open(aliceId, "101000000002"));
        assertTrue(accounts.findOwnedById(mine.getAccountId(), bobId).isEmpty());
    }

    @Test
    void listByCustomerReturnsOnlyTheirAccounts() {
        accounts.insert(SavingsAccount.open(aliceId, "101000000003"));
        accounts.insert(SavingsAccount.open(aliceId, "101000000004"));
        accounts.insert(SavingsAccount.open(bobId, "101000000005"));

        List<Account> alices = accounts.listByCustomer(aliceId);
        assertEquals(2, alices.size());
        assertTrue(alices.stream().allMatch(a -> a.getCustomerId() == aliceId));
    }

    @Test
    void statusCanBeUpdated() {
        Account a = accounts.insert(SavingsAccount.open(aliceId, "101000000006"));
        accounts.updateStatus(a.getAccountId(), AccountStatus.FROZEN);
        assertEquals(AccountStatus.FROZEN,
                accounts.findByNumber("101000000006").orElseThrow().getStatus());
        assertEquals(AccountType.SAVINGS, a.type());
    }
}