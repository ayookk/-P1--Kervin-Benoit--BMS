package com.example.banking.service;

import static org.junit.jupiter.api.Assertions.*;

import com.example.banking.exception.AuthorizationException;
import com.example.banking.exception.ValidationException;
import com.example.banking.fakes.InMemoryAccountDAO;
import com.example.banking.fakes.MutableClock;
import com.example.banking.model.Account;
import com.example.banking.model.AccountStatus;
import com.example.banking.model.DepositAccount;
import com.example.banking.model.LoanAccount;
import com.example.banking.model.Money;
import com.example.banking.security.SessionContext;
import com.example.banking.util.ReferenceGenerator;
import com.example.banking.validation.AccountValidator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountServiceTest {

    private InMemoryAccountDAO dao;
    private AccountService service;
    private SessionContext alice;
    private SessionContext bob;

    @BeforeEach
    void setUp() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-28T12:00:00Z"));
        dao = new InMemoryAccountDAO();
        service = new AccountService(dao, new ReferenceGenerator(clock),
                new AccountValidator(Money.of("1000000.00")), clock);
        alice = new SessionContext(1, "alice", clock.instant());
        bob = new SessionContext(2, "bob", clock.instant());
    }

    @Test
    void newSavingsAccountStartsAtZeroWithValidNumber() {
        Account a = service.openSavings(alice);
        assertEquals(Money.ZERO, a.getBalance());
        assertEquals(1L, a.getCustomerId());
        assertTrue(a.getAccountNumber().startsWith("101"));
        assertTrue(ReferenceGenerator.isValidAccountNumber(a.getAccountNumber()));
    }

    @Test
    void loanRecordsTheAmountOwed() {
        LoanAccount loan = (LoanAccount) service.openLoan(alice, Money.of("5000"), new BigDecimal("6.5"), 24);
        assertEquals(Money.of("5000.00"), loan.getBalance());
        assertEquals(LocalDate.of(2026, 10, 28), loan.getStartDate());
        assertFalse(loan.canDebit());   // a loan is never spendable cash
    }

    @Test
    void invalidLoanTermsAreAllReportedTogether() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> service.openLoan(alice, Money.of("0"), new BigDecimal("150"), 0));
        assertTrue(e.getMessage().contains("Loan amount"));
        assertTrue(e.getMessage().contains("Interest rate"));
        assertTrue(e.getMessage().contains("Tenure"));
    }

    @Test
    void loanAboveTheConfiguredLimitIsRejected() {
        assertThrows(ValidationException.class,
                () -> service.openLoan(alice, Money.of("2000000"), new BigDecimal("5"), 12));
    }

    @Test
    void T06_listShowsOnlyMyAccounts() {
        service.openSavings(alice);
        service.openCurrent(alice);
        service.openSavings(bob);
        assertEquals(2, service.listMyAccounts(alice).size());
        assertEquals(1, service.listMyAccounts(bob).size());
    }

    @Test
    void T06_anotherCustomersAccountLooksNotFound() {
        Account bobs = service.openSavings(bob);
        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> service.getOwnedAccount(alice, bobs.getAccountId()));
        assertEquals("Account not found.", e.getMessage());
    }

    @Test
    void depositIsMarkedMaturedWhenListedAfterItsMaturityDate() {
        // opened Jan 1 for 6 months -> matured Jul 1, and "today" is Oct 28
        dao.insert(DepositAccount.open(1L, "103000000001", Money.of("1000"), 6,
                BigDecimal.ZERO, LocalDate.of(2026, 1, 1)));
        Account listed = service.listMyAccounts(alice).get(0);
        assertEquals(AccountStatus.MATURED, listed.getStatus());
        assertEquals(1, dao.statusUpdates());
    }

    @Test
    void depositNotYetDueStaysActive() {
        dao.insert(DepositAccount.open(1L, "103000000002", Money.of("1000"), 6,
                BigDecimal.ZERO, LocalDate.of(2026, 10, 1)));
        assertEquals(AccountStatus.ACTIVE, service.listMyAccounts(alice).get(0).getStatus());
        assertEquals(0, dao.statusUpdates());
    }
}