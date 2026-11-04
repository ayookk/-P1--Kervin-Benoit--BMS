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
import com.example.banking.fakes.MutableClock;
import com.example.banking.model.Account;
import com.example.banking.model.AccountStatus;
import com.example.banking.model.BankTransaction;
import com.example.banking.model.Customer;
import com.example.banking.model.DepositAccount;
import com.example.banking.model.LoanAccount;
import com.example.banking.model.Money;
import com.example.banking.model.TransactionType;
import com.example.banking.security.SessionContext;
import com.example.banking.util.ConnectionFactory;
import com.example.banking.util.JdbcTransaction;
import com.example.banking.util.ReferenceGenerator;
import com.example.banking.validation.AccountValidator;
import com.example.banking.validation.TransactionValidator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DepositLoanServiceTest {

    private final ConnectionFactory connections = TestDatabase.connections();
    private final CustomerDAO customers = new JdbcCustomerDAO(connections, 10);
    private final AccountDAO accounts = new JdbcAccountDAO(connections, 10, Money.ZERO);
    private final TransactionDAO ledger = new JdbcTransactionDAO(connections, 10);
    private final JdbcTransaction tx = new JdbcTransaction(connections, 3);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-03T12:00:00Z"));
    private final ReferenceGenerator refs = new ReferenceGenerator(clock);
    private final AccountValidator accountValidator = new AccountValidator(Money.of("1000000.00"));
    private final AccountService accountService = new AccountService(accounts, refs, accountValidator, clock);
    private final DepositLoanService service = new DepositLoanService(accounts, ledger,
            new JdbcTransactionAuditDAO(connections, 10), tx, new TransactionValidator(Money.of("1000000.00")),
            accountValidator, accountService, refs, clock);

    private SessionContext alice;
    private Account aliceSavings;
    private Account aliceCurrent;
    private Account bobSavings;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(connections);
        long aliceId = customers.insert(new Customer("Alice", "alice@example.com", null, "alice", "h")).getCustomerId();
        long bobId = customers.insert(new Customer("Bob", "bob@example.com", null, "bob", "h")).getCustomerId();
        alice = new SessionContext(aliceId, "alice", clock.instant());
        SessionContext bob = new SessionContext(bobId, "bob", clock.instant());

        aliceSavings = accountService.openSavings(alice);
        aliceCurrent = accountService.openCurrent(alice);
        bobSavings = accountService.openSavings(bob);
        setBalance(aliceSavings, "1000.00");                 // Alice starts with $1000 in Savings
    }

    private void setBalance(Account a, String amount) {
        tx.execute(conn -> {
            accounts.updateBalance(conn, a.getAccountId(), Money.of(amount));
            return null;
        });
    }

    private Account reload(Account a) {
        return accounts.findByNumber(a.getAccountNumber()).orElseThrow();
    }

    // ---------- fixed-term deposits ----------

    @Test
    void openingADepositMovesMoneyFromSavingsInOneTransaction() {
        DepositLoanService.DepositOpening opened =
                service.openDeposit(alice, aliceSavings.getAccountId(), Money.of("300.00"), 6, new BigDecimal("3.5"));

        assertEquals(Money.of("700.00"), reload(aliceSavings).getBalance());
        DepositAccount dep = (DepositAccount) reload(opened.account());
        assertEquals(Money.of("300.00"), dep.getBalance());
        assertEquals(LocalDate.of(2027, 5, 3), dep.getMaturityDate());

        BankTransaction row = ledger.findByReference(opened.transaction().getReferenceNumber()).orElseThrow();
        assertEquals(TransactionType.TRANSFER, row.getType());
        assertEquals(aliceSavings.getAccountId(), row.getSourceAccountId());
        assertEquals(dep.getAccountId(), row.getDestinationAccountId());
    }

    @Test
    void openingADepositWithoutEnoughFundsCreatesNothing() {
        assertThrows(InsufficientFundsException.class,
                () -> service.openDeposit(alice, aliceSavings.getAccountId(), Money.of("2000.00"), 6, BigDecimal.ONE));
        assertEquals(Money.of("1000.00"), reload(aliceSavings).getBalance());
        assertEquals(2, accounts.listByCustomer(alice.getCustomerId()).size());   // no deposit account appeared
    }

    @Test
    void badDepositTermsAreRejected() {
        assertThrows(ValidationException.class,
                () -> service.openDeposit(alice, aliceSavings.getAccountId(), Money.of("50.00"), 6, BigDecimal.ONE));
        assertThrows(ValidationException.class,
                () -> service.openDeposit(alice, aliceSavings.getAccountId(), Money.of("300.00"), 0, BigDecimal.ONE));
    }

    @Test
    void T16_settlingBeforeMaturityIsRefused() {
        DepositAccount dep = service.openDeposit(alice, aliceSavings.getAccountId(),
                Money.of("300.00"), 6, BigDecimal.ONE).account();

        assertThrows(AccountNotEligibleException.class,
                () -> service.settleDeposit(alice, dep.getAccountId(), aliceCurrent.getAccountId()));
        assertEquals(Money.of("300.00"), reload(dep).getBalance());
        assertEquals(Money.ZERO, reload(aliceCurrent).getBalance());
    }

    @Test
    void T16_settlingAfterMaturityPaysOutAndCloses() {
        DepositAccount dep = service.openDeposit(alice, aliceSavings.getAccountId(),
                Money.of("300.00"), 6, BigDecimal.ONE).account();
        clock.advance(Duration.ofDays(200));                       // well past the 6-month maturity

        service.settleDeposit(alice, dep.getAccountId(), aliceCurrent.getAccountId());

        assertEquals(Money.of("300.00"), reload(aliceCurrent).getBalance());
        Account closed = reload(dep);
        assertEquals(Money.ZERO, closed.getBalance());
        assertEquals(AccountStatus.CLOSED, closed.getStatus());
    }

    @Test
    void cannotSettleIntoSomeoneElsesAccount() {
        DepositAccount dep = service.openDeposit(alice, aliceSavings.getAccountId(),
                Money.of("300.00"), 6, BigDecimal.ONE).account();
        clock.advance(Duration.ofDays(200));
        assertThrows(AuthorizationException.class,
                () -> service.settleDeposit(alice, dep.getAccountId(), bobSavings.getAccountId()));
    }

    // ---------- loan repayment ----------

    private LoanAccount openLoan(String principal) {
        return (LoanAccount) accountService.openLoan(alice, Money.of(principal), new BigDecimal("5"), 12);
    }

    @Test
    void T17_partialRepaymentReducesTheLoan() {
        LoanAccount loan = openLoan("500.00");
        service.repayLoan(alice, aliceSavings.getAccountId(), loan.getAccountId(), Money.of("200.00"));

        assertEquals(Money.of("800.00"), reload(aliceSavings).getBalance());
        Account after = reload(loan);
        assertEquals(Money.of("300.00"), after.getBalance());
        assertEquals(AccountStatus.ACTIVE, after.getStatus());
    }

    @Test
    void T17_exactRepaymentClosesTheLoan() {
        LoanAccount loan = openLoan("500.00");
        service.repayLoan(alice, aliceSavings.getAccountId(), loan.getAccountId(), Money.of("500.00"));

        Account after = reload(loan);
        assertEquals(Money.ZERO, after.getBalance());
        assertEquals(AccountStatus.CLOSED, after.getStatus());
    }

    @Test
    void T17_overpaymentIsRefused() {
        LoanAccount loan = openLoan("500.00");
        assertThrows(ValidationException.class,
                () -> service.repayLoan(alice, aliceSavings.getAccountId(), loan.getAccountId(), Money.of("600.00")));
        assertEquals(Money.of("1000.00"), reload(aliceSavings).getBalance());
        assertEquals(Money.of("500.00"), reload(loan).getBalance());
    }

    @Test
    void repaymentNeedsEnoughFunds() {
        LoanAccount loan = openLoan("5000.00");
        assertThrows(InsufficientFundsException.class,
                () -> service.repayLoan(alice, aliceSavings.getAccountId(), loan.getAccountId(), Money.of("2000.00")));
        assertEquals(Money.of("5000.00"), reload(loan).getBalance());   // rolled back: still owed in full
    }
}