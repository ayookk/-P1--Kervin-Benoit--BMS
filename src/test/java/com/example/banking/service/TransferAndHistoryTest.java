package com.example.banking.service;

import static org.junit.jupiter.api.Assertions.*;

import com.example.banking.TestDatabase;
import com.example.banking.dao.CustomerDAO;
import com.example.banking.dao.jdbc.JdbcAccountDAO;
import com.example.banking.dao.jdbc.JdbcCustomerDAO;
import com.example.banking.dao.jdbc.JdbcTransactionAuditDAO;
import com.example.banking.dao.jdbc.JdbcTransactionDAO;
import com.example.banking.exception.AccountNotEligibleException;
import com.example.banking.exception.AuthorizationException;
import com.example.banking.exception.PersistenceException;
import com.example.banking.exception.ValidationException;
import com.example.banking.model.AccountStatus;
import com.example.banking.model.AccountType;
import com.example.banking.model.BankTransaction;
import com.example.banking.model.CurrentAccount;
import com.example.banking.model.Customer;
import com.example.banking.model.LoanAccount;
import com.example.banking.model.Money;
import com.example.banking.model.SavingsAccount;
import com.example.banking.model.TransactionType;
import com.example.banking.security.SessionContext;
import com.example.banking.util.ConnectionFactory;
import com.example.banking.util.JdbcTransaction;
import com.example.banking.util.ReferenceGenerator;
import com.example.banking.validation.TransactionValidator;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransferAndHistoryTest {

    /** Real JDBC DAO that can be told to crash when updating ONE specific account's balance. */
    static class CrashOnCreditAccountDAO extends JdbcAccountDAO {
        Long crashOnAccountId;

        CrashOnCreditAccountDAO(ConnectionFactory cf) {
            super(cf, 10, Money.ZERO);
        }

        @Override
        public void updateBalance(Connection conn, long accountId, Money newBalance) {
            if (crashOnAccountId != null && crashOnAccountId == accountId) {
                throw new PersistenceException("simulated crash while crediting", new SQLException("boom"));
            }
            super.updateBalance(conn, accountId, newBalance);
        }
    }

    /** Real ledger DAO that can be told to crash when writing the ledger row. */
    static class CrashingLedger extends JdbcTransactionDAO {
        boolean crash;

        CrashingLedger(ConnectionFactory cf) {
            super(cf, 10);
        }

        @Override
        public BankTransaction insert(Connection conn, BankTransaction tx) {
            if (crash) {
                throw new PersistenceException("simulated crash writing ledger", new SQLException("boom"));
            }
            return super.insert(conn, tx);
        }
    }

    private final ConnectionFactory connections = TestDatabase.connections();
    private final CustomerDAO customers = new JdbcCustomerDAO(connections, 10);
    private final CrashOnCreditAccountDAO accounts = new CrashOnCreditAccountDAO(connections);
    private final CrashingLedger ledger = new CrashingLedger(connections);
    private final ReferenceGenerator refs = new ReferenceGenerator(Clock.systemUTC());
    private final TransactionService service = new TransactionService(accounts, ledger,
            new JdbcTransactionAuditDAO(connections, 10), new JdbcTransaction(connections, 3),
            new TransactionValidator(Money.of("1000.00")), refs, 50);

    private SessionContext alice;
    private long aliceSavingsId;
    private String aliceSavingsNo;
    private String aliceCurrentNo;
    private String aliceLoanNo;
    private long bobSavingsId;
    private String bobSavingsNo;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(connections);
        long aliceId = customers.insert(new Customer("Alice", "alice@example.com", null, "alice", "h")).getCustomerId();
        long bobId = customers.insert(new Customer("Bob", "bob@example.com", null, "bob", "h")).getCustomerId();

        aliceSavingsNo = refs.newAccountNumber(AccountType.SAVINGS);
        aliceCurrentNo = refs.newAccountNumber(AccountType.CURRENT);
        aliceLoanNo = refs.newAccountNumber(AccountType.LOAN);
        bobSavingsNo = refs.newAccountNumber(AccountType.SAVINGS);

        aliceSavingsId = accounts.insert(SavingsAccount.open(aliceId, aliceSavingsNo)).getAccountId();
        accounts.insert(CurrentAccount.open(aliceId, aliceCurrentNo));
        accounts.insert(LoanAccount.open(aliceId, aliceLoanNo, Money.of("500.00"),
                BigDecimal.ONE, 12, LocalDate.of(2026, 10, 29)));
        bobSavingsId = accounts.insert(SavingsAccount.open(bobId, bobSavingsNo)).getAccountId();

        alice = new SessionContext(aliceId, "alice", Instant.now());
        service.deposit(alice, aliceSavingsId, Money.of("100.00"));   // Alice starts with $100
    }

    private Money balance(String accountNumber) {
        return accounts.findByNumber(accountNumber).orElseThrow().getBalance();
    }

    private int auditRows(String outcome, String failureCode) throws Exception {
        try (Connection c = connections.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM transaction_audit WHERE outcome = ? AND failure_code = ?")) {
            ps.setString(1, outcome);
            ps.setString(2, failureCode);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ---------- transfer: success ----------

    @Test
    void T09_transferMovesMoneyAndWritesOneLedgerRow() {
        BankTransaction t = service.transfer(alice, aliceSavingsId, bobSavingsNo, Money.of("40.00"));

        assertEquals(Money.of("60.00"), balance(aliceSavingsNo));
        assertEquals(Money.of("40.00"), balance(bobSavingsNo));

        BankTransaction row = ledger.findByReference(t.getReferenceNumber()).orElseThrow();
        assertEquals(TransactionType.TRANSFER, row.getType());
        assertEquals(aliceSavingsId, row.getSourceAccountId());
        assertEquals(bobSavingsId, row.getDestinationAccountId());
        assertEquals(Money.of("60.00"), row.getSourceBalanceAfter());
        assertEquals(Money.of("40.00"), row.getDestinationBalanceAfter());
    }

    @Test
    void transferBetweenMyOwnAccountsWorks() {
        service.transfer(alice, aliceSavingsId, aliceCurrentNo, Money.of("25.50"));
        assertEquals(Money.of("74.50"), balance(aliceSavingsNo));
        assertEquals(Money.of("25.50"), balance(aliceCurrentNo));
    }

    // ---------- transfer: rollback (doc T10) ----------

    @Test
    void T10_crashAfterSourceDebitRollsBackBothBalances() throws Exception {
        accounts.crashOnAccountId = bobSavingsId;    // crash while crediting Bob, AFTER Alice was debited

        assertThrows(PersistenceException.class,
                () -> service.transfer(alice, aliceSavingsId, bobSavingsNo, Money.of("40.00")));

        assertEquals(Money.of("100.00"), balance(aliceSavingsNo));    // Alice's debit was undone
        assertEquals(Money.ZERO, balance(bobSavingsNo));              // Bob got nothing
        assertEquals(1, ledger.countByAccount(aliceSavingsId));       // only the setup deposit
        assertEquals(0, ledger.countByAccount(bobSavingsId));         // no SUCCESS transfer row
        assertEquals(1, auditRows("ROLLED_BACK", "DB_ERROR"));        // but the failure IS audited
    }

    @Test
    void crashWhileWritingLedgerAlsoRollsBackBothBalances() {
        ledger.crash = true;                         // both balances updated, then the ledger insert fails

        assertThrows(PersistenceException.class,
                () -> service.transfer(alice, aliceSavingsId, bobSavingsNo, Money.of("40.00")));

        assertEquals(Money.of("100.00"), balance(aliceSavingsNo));
        assertEquals(Money.ZERO, balance(bobSavingsNo));
    }

    // ---------- transfer: rejected before commit (doc T11) ----------

    @Test
    void T11_cannotTransferToTheSameAccount() {
        assertThrows(ValidationException.class,
                () -> service.transfer(alice, aliceSavingsId, aliceSavingsNo, Money.of("10.00")));
        assertEquals(Money.of("100.00"), balance(aliceSavingsNo));
    }

    @Test
    void T11_cannotTransferIntoALoan() {
        assertThrows(AccountNotEligibleException.class,
                () -> service.transfer(alice, aliceSavingsId, aliceLoanNo, Money.of("10.00")));
        assertEquals(Money.of("100.00"), balance(aliceSavingsNo));
    }

    @Test
    void T11_cannotTransferIntoAFrozenAccount() {
        accounts.updateStatus(bobSavingsId, AccountStatus.FROZEN);
        assertThrows(AccountNotEligibleException.class,
                () -> service.transfer(alice, aliceSavingsId, bobSavingsNo, Money.of("10.00")));
        assertEquals(Money.of("100.00"), balance(aliceSavingsNo));
        assertEquals(Money.ZERO, balance(bobSavingsNo));
    }

    @Test
    void T06_cannotTransferOutOfSomeoneElsesAccount() {
        assertThrows(AuthorizationException.class,
                () -> service.transfer(alice, bobSavingsId, aliceSavingsNo, Money.of("10.00")));
    }

    @Test
    void mistypedDestinationNumberIsRejectedBeforeTheDatabase() {
        char[] typo = bobSavingsNo.toCharArray();
        typo[11] = (char) ('0' + (typo[11] - '0' + 1) % 10);       // wrong check digit
        assertThrows(ValidationException.class,
                () -> service.transfer(alice, aliceSavingsId, new String(typo), Money.of("10.00")));
    }

    // ---------- history (doc T15) ----------

    @Test
    void T15_historyPagesAreNewestFirstWithNoGapsOrDuplicates() {
        for (int i = 1; i <= 6; i++) {                               // plus the $100 setup deposit = 7 rows
            service.deposit(alice, aliceSavingsId, Money.of(i + ".00"));
        }
        HistoryPage first = service.history(alice, aliceSavingsId, 0, 3);
        HistoryPage second = service.history(alice, aliceSavingsId, 1, 3);
        HistoryPage third = service.history(alice, aliceSavingsId, 2, 3);

        assertEquals(7, first.totalItems());
        assertEquals(3, first.totalPages());
        assertEquals(Money.of("6.00"), first.items().get(0).getAmount());   // newest first
        assertEquals(1, third.items().size());
        assertFalse(third.hasNext());

        Set<String> refsSeen = new HashSet<>();
        for (HistoryPage p : new HistoryPage[] {first, second, third}) {
            p.items().forEach(t -> refsSeen.add(t.getReferenceNumber()));
        }
        assertEquals(7, refsSeen.size());                             // every row exactly once
    }

    @Test
    void historyOfSomeoneElsesAccountIsDenied() {
        assertThrows(AuthorizationException.class, () -> service.history(alice, bobSavingsId, 0, 5));
    }

    @Test
    void invalidPageRequestsAreRejected() {
        assertThrows(ValidationException.class, () -> service.history(alice, aliceSavingsId, -1, 5));
        assertThrows(ValidationException.class, () -> service.history(alice, aliceSavingsId, 0, 51));
        assertThrows(ValidationException.class, () -> service.history(alice, aliceSavingsId, 5, 5));
    }
}