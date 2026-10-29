package com.example.banking.service;

import static org.junit.jupiter.api.Assertions.*;

import com.example.banking.TestDatabase;
import com.example.banking.dao.AccountDAO;
import com.example.banking.dao.CustomerDAO;
import com.example.banking.dao.TransactionDAO;
import com.example.banking.dao.jdbc.JdbcAccountDAO;
import com.example.banking.dao.jdbc.JdbcCustomerDAO;
import com.example.banking.dao.jdbc.JdbcTransactionAuditDAO;
import com.example.banking.dao.jdbc.JdbcTransactionDAO;
import com.example.banking.exception.AccountNotEligibleException;
import com.example.banking.exception.AuthorizationException;
import com.example.banking.exception.InsufficientFundsException;
import com.example.banking.exception.ValidationException;
import com.example.banking.model.AccountStatus;
import com.example.banking.model.BankTransaction;
import com.example.banking.model.Customer;
import com.example.banking.model.LoanAccount;
import com.example.banking.model.Money;
import com.example.banking.model.SavingsAccount;
import com.example.banking.security.SessionContext;
import com.example.banking.util.ConnectionFactory;
import com.example.banking.util.JdbcTransaction;
import com.example.banking.util.ReferenceGenerator;
import com.example.banking.validation.TransactionValidator;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransactionServiceTest {

    private final ConnectionFactory connections = TestDatabase.connections();
    private final CustomerDAO customers = new JdbcCustomerDAO(connections, 10);
    private final AccountDAO accounts = new JdbcAccountDAO(connections, 10, Money.ZERO);
    private final TransactionDAO ledger = new JdbcTransactionDAO(connections, 10);
    private final TransactionService service = new TransactionService(accounts, ledger,
            new JdbcTransactionAuditDAO(connections, 10), new JdbcTransaction(connections, 3),
            new TransactionValidator(Money.of("1000.00")), new ReferenceGenerator(Clock.systemUTC()));

    private SessionContext alice;
    private long aliceSavings;
    private long aliceLoan;
    private long bobSavings;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(connections);
        long aliceId = customers.insert(new Customer("Alice", "alice@example.com", null, "alice", "h")).getCustomerId();
        long bobId = customers.insert(new Customer("Bob", "bob@example.com", null, "bob", "h")).getCustomerId();
        aliceSavings = accounts.insert(SavingsAccount.open(aliceId, "101000000001")).getAccountId();
        aliceLoan = accounts.insert(LoanAccount.open(aliceId, "104000000001", Money.of("500.00"),
                BigDecimal.ONE, 12, LocalDate.of(2026, 10, 29))).getAccountId();
        bobSavings = accounts.insert(SavingsAccount.open(bobId, "101000000002")).getAccountId();
        alice = new SessionContext(aliceId, "alice", Instant.now());
    }

    private Money balanceOf(long accountId) {
        return accounts.findByNumber(accounts.listByCustomer(ownerOf(accountId)).stream()
                .filter(a -> a.getAccountId() == accountId).findFirst().orElseThrow().getAccountNumber())
                .orElseThrow().getBalance();
    }

    private long ownerOf(long accountId) {
        return accountId == bobSavings
                ? accounts.findByNumber("101000000002").orElseThrow().getCustomerId()
                : alice.getCustomerId();
    }

    private int auditRows(String outcome, String failureCode) throws Exception {
        String sql = "SELECT COUNT(*) FROM transaction_audit WHERE outcome = ? AND "
                   + (failureCode == null ? "failure_code IS NULL" : "failure_code = ?");
        try (Connection c = connections.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, outcome);
            if (failureCode != null) {
                ps.setString(2, failureCode);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void T07_depositIncreasesBalanceAndWritesLedgerAndAudit() throws Exception {
        BankTransaction t = service.deposit(alice, aliceSavings, Money.of("100.00"));

        assertEquals(Money.of("100.00"), balanceOf(aliceSavings));
        BankTransaction row = ledger.findByReference(t.getReferenceNumber()).orElseThrow();
        assertEquals(Money.of("100.00"), row.getDestinationBalanceAfter());
        assertEquals(1, auditRows("SUCCESS", null));
    }

    @Test
    void withdrawalWithinBalanceSucceeds() {
        service.deposit(alice, aliceSavings, Money.of("100.00"));
        BankTransaction t = service.withdraw(alice, aliceSavings, Money.of("30.50"));
        assertEquals(Money.of("69.50"), t.getSourceBalanceAfter());
        assertEquals(Money.of("69.50"), balanceOf(aliceSavings));
        assertEquals(2, ledger.countByAccount(aliceSavings));
    }

    @Test
    void T08_withdrawingMoreThanBalanceChangesNothing() throws Exception {
        service.deposit(alice, aliceSavings, Money.of("50.00"));

        assertThrows(InsufficientFundsException.class,
                () -> service.withdraw(alice, aliceSavings, Money.of("80.00")));

        assertEquals(Money.of("50.00"), balanceOf(aliceSavings));       // balance unchanged
        assertEquals(1, ledger.countByAccount(aliceSavings));           // only the deposit in the ledger
        assertEquals(1, auditRows("REJECTED", "INSUFFICIENT_FUNDS"));   // but the attempt IS audited
    }

    @Test
    void T06_cannotDepositIntoAnotherCustomersAccount() throws Exception {
        assertThrows(AuthorizationException.class,
                () -> service.deposit(alice, bobSavings, Money.of("10.00")));
        assertEquals(Money.ZERO, balanceOf(bobSavings));
        assertEquals(1, auditRows("REJECTED", "NOT_OWNER"));
    }

    @Test
    void loanAccountCannotTakeDeposits() throws Exception {
        assertThrows(AccountNotEligibleException.class,
                () -> service.deposit(alice, aliceLoan, Money.of("10.00")));
        assertEquals(1, auditRows("REJECTED", "NOT_ELIGIBLE"));
    }

    @Test
    void frozenAccountCannotWithdraw() {
        service.deposit(alice, aliceSavings, Money.of("100.00"));
        accounts.updateStatus(aliceSavings, AccountStatus.FROZEN);
        assertThrows(AccountNotEligibleException.class,
                () -> service.withdraw(alice, aliceSavings, Money.of("10.00")));
        assertEquals(Money.of("100.00"), balanceOf(aliceSavings));
    }

    @Test
    void T13_zeroAndOverLimitAmountsAreRejectedBeforeTouchingTheDatabase() {
        assertThrows(ValidationException.class, () -> service.deposit(alice, aliceSavings, Money.of("0")));
        assertThrows(ValidationException.class, () -> service.deposit(alice, aliceSavings, Money.of("1000.01")));
        assertThrows(ValidationException.class, () -> Money.of("10.555"));    // 3 decimals
        assertThrows(ValidationException.class, () -> Money.of("-5"));        // negative
        assertEquals(0, ledger.countByAccount(aliceSavings));
    }
}