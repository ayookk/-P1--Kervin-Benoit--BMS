package com.example.banking.service;

import static org.junit.jupiter.api.Assertions.*;

import com.example.banking.TestDatabase;
import com.example.banking.dao.CustomerDAO;
import com.example.banking.dao.TransactionDAO;
import com.example.banking.dao.jdbc.JdbcAccountDAO;
import com.example.banking.dao.jdbc.JdbcCustomerDAO;
import com.example.banking.dao.jdbc.JdbcTransactionAuditDAO;
import com.example.banking.dao.jdbc.JdbcTransactionDAO;
import com.example.banking.exception.InsufficientFundsException;
import com.example.banking.model.Account;
import com.example.banking.model.AccountType;
import com.example.banking.model.Customer;
import com.example.banking.model.Money;
import com.example.banking.model.SavingsAccount;
import com.example.banking.security.SessionContext;
import com.example.banking.util.ConnectionFactory;
import com.example.banking.util.JdbcTransaction;
import com.example.banking.util.ReferenceGenerator;
import com.example.banking.validation.TransactionValidator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Doc T14: simultaneous operations must not overspend or deadlock (row locks + ascending lock order). */
class ConcurrencyTest {

    private final ConnectionFactory connections = TestDatabase.connections();
    private final CustomerDAO customers = new JdbcCustomerDAO(connections, 10);
    private final JdbcAccountDAO accounts = new JdbcAccountDAO(connections, 10, Money.ZERO);
    private final TransactionDAO ledger = new JdbcTransactionDAO(connections, 10);
    private final ReferenceGenerator refs = new ReferenceGenerator(Clock.systemUTC());
    private final TransactionService service = new TransactionService(accounts, ledger,
            new JdbcTransactionAuditDAO(connections, 10), new JdbcTransaction(connections, 3),
            new TransactionValidator(Money.of("1000.00")), refs, 50);

    private SessionContext alice;
    private SessionContext bob;
    private Account aliceSavings;
    private Account bobSavings;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(connections);
        long aliceId = customers.insert(new Customer("Alice", "alice@example.com", null, "alice", "h")).getCustomerId();
        long bobId = customers.insert(new Customer("Bob", "bob@example.com", null, "bob", "h")).getCustomerId();
        alice = new SessionContext(aliceId, "alice", Instant.now());
        bob = new SessionContext(bobId, "bob", Instant.now());
        aliceSavings = accounts.insert(SavingsAccount.open(aliceId, refs.newAccountNumber(AccountType.SAVINGS)));
        bobSavings = accounts.insert(SavingsAccount.open(bobId, refs.newAccountNumber(AccountType.SAVINGS)));
        service.deposit(alice, aliceSavings.getAccountId(), Money.of("100.00"));
    }

    private Money balance(Account a) {
        return accounts.findByNumber(a.getAccountNumber()).orElseThrow().getBalance();
    }

    /**
     * Starts every task at the same instant (the "starting gun" latch) and waits for all.
     * Returns null for a task that succeeded, or the exception it threw.
     */
    private List<Throwable> runAllAtOnce(List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch startingGun = new CountDownLatch(1);
        List<Future<Throwable>> futures = new ArrayList<>();
        for (Runnable task : tasks) {
            Callable<Throwable> job = () -> {
                startingGun.await();               // every thread waits here...
                try {
                    task.run();
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            };
            futures.add(pool.submit(job));
        }
        startingGun.countDown();                   // ...until this fires them all together
        List<Throwable> results = new ArrayList<>();
        for (Future<Throwable> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        return results;
    }

    @Test
    void T14_twoSimultaneousWithdrawalsCannotOverspend() throws Exception {
        Runnable withdraw80 = () -> service.withdraw(alice, aliceSavings.getAccountId(), Money.of("80.00"));

        List<Throwable> results = runAllAtOnce(List.of(withdraw80, withdraw80));

        long succeeded = results.stream().filter(r -> r == null).count();
        long refused = results.stream().filter(r -> r instanceof InsufficientFundsException).count();
        assertEquals(1, succeeded, "exactly one withdrawal may succeed");
        assertEquals(1, refused, "the other must see the updated balance and be refused");
        assertEquals(Money.of("20.00"), balance(aliceSavings));               // never -60
        assertEquals(2, ledger.countByAccount(aliceSavings.getAccountId()));  // deposit + ONE withdrawal
    }

    @Test
    void T14_oppositeTransfersDoNotDeadlockAndMoneyIsConserved() throws Exception {
        service.deposit(bob, bobSavings.getAccountId(), Money.of("100.00"));

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> service.transfer(alice, aliceSavings.getAccountId(),
                    bobSavings.getAccountNumber(), Money.of("1.00")));       // A -> B
            tasks.add(() -> service.transfer(bob, bobSavings.getAccountId(),
                    aliceSavings.getAccountNumber(), Money.of("1.00")));     // B -> A, at the same time
        }

        List<Throwable> results = runAllAtOnce(tasks);

        results.forEach(r -> assertNull(r, () -> "a transfer failed: " + r));
        assertEquals(Money.of("100.00"), balance(aliceSavings));   // 10 out, 10 in
        assertEquals(Money.of("100.00"), balance(bobSavings));
        // every one of the 20 transfers is in the ledger exactly once (plus one setup deposit each)
        assertEquals(21, ledger.countByAccount(aliceSavings.getAccountId()));
        assertEquals(21, ledger.countByAccount(bobSavings.getAccountId()));
    }
}