package com.example.banking.app;

import com.example.banking.service.DepositLoanService;
import com.example.banking.dao.AccountDAO;
import com.example.banking.dao.CustomerDAO;
import com.example.banking.dao.LoginAuditDAO;
import com.example.banking.dao.TransactionAuditDAO;
import com.example.banking.dao.TransactionDAO;
import com.example.banking.dao.jdbc.JdbcAccountDAO;
import com.example.banking.dao.jdbc.JdbcCustomerDAO;
import com.example.banking.dao.jdbc.JdbcLoginAuditDAO;
import com.example.banking.dao.jdbc.JdbcTransactionAuditDAO;
import com.example.banking.dao.jdbc.JdbcTransactionDAO;
import com.example.banking.security.PasswordHasher;
import com.example.banking.security.Pbkdf2PasswordHasher;
import com.example.banking.service.AccountService;
import com.example.banking.service.AuthService;
import com.example.banking.service.TransactionService;
import com.example.banking.util.AppConfig;
import com.example.banking.util.ConnectionFactory;
import com.example.banking.util.JdbcTransaction;
import com.example.banking.util.ReferenceGenerator;
import com.example.banking.validation.AccountValidator;
import com.example.banking.validation.CustomerValidator;
import com.example.banking.validation.TransactionValidator; 
import com.example.banking.service.SessionService;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point: builds every object once, wires them together, starts the menu. */
public class BankingApp {

    private static final Logger log = LoggerFactory.getLogger(BankingApp.class);
    private static final int MAX_TX_ATTEMPTS = 3;   // doc section 12: retry deadlocks at most 3 times

    public static void main(String[] args) {
        try {
            AppConfig config = AppConfig.load();
            ConnectionFactory connections = new ConnectionFactory(config);
            checkDatabase(connections);

            Clock clock = Clock.systemDefaultZone();
            int queryTimeout = config.dbQueryTimeoutSeconds();

            // ---- DAOs ----
            CustomerDAO customerDao = new JdbcCustomerDAO(connections, queryTimeout);
            LoginAuditDAO loginAuditDao = new JdbcLoginAuditDAO(connections, queryTimeout);
            AccountDAO accountDao = new JdbcAccountDAO(connections, queryTimeout, config.savingsMinimumBalance());
            TransactionDAO ledger = new JdbcTransactionDAO(connections, queryTimeout);
            TransactionAuditDAO txAudit = new JdbcTransactionAuditDAO(connections, queryTimeout);

            // ---- helpers ----
            PasswordHasher hasher = new Pbkdf2PasswordHasher(config.passwordHashIterations());
            ReferenceGenerator refs = new ReferenceGenerator(clock);
            JdbcTransaction tx = new JdbcTransaction(connections, MAX_TX_ATTEMPTS);

            // ---- services ----
            AuthService auth = new AuthService(customerDao, loginAuditDao, hasher, new CustomerValidator(),
                    clock, config.authMaxFailedLogins(), Duration.ofMinutes(config.authLockMinutes()));
            AccountService accountService = new AccountService(accountDao, refs,
                    new AccountValidator(config.txMaxAmount()), clock);
            TransactionService transactionService = new TransactionService(accountDao, ledger, txAudit, tx,
                                    new TransactionValidator(config.txMaxAmount()), refs, config.historyMaxPageSize());

                        SessionService sessionService = new SessionService(clock,
                    Duration.ofSeconds(config.sessionIdleTimeoutSeconds()), loginAuditDao);

                        DepositLoanService depositLoanService = new DepositLoanService(accountDao, ledger, txAudit, tx,
                    new TransactionValidator(config.txMaxAmount()), new AccountValidator(config.txMaxAmount()),
                    accountService, refs, clock);

            AppServices services = new AppServices(auth, accountService, transactionService,
                    sessionService, depositLoanService);

            log.info("Application started");
            new MainMenu(new ConsoleInput(System.out), System.out, services).run();
            log.info("Application stopped");
        } catch (IllegalStateException e) {
            System.err.println("Configuration problem: " + e.getMessage());
        } catch (SQLException e) {
            log.error("Database unreachable at startup", e);
            System.err.println("Cannot reach the database. Is MySQL running? (" + e.getMessage() + ")");
        } catch (ConsoleInput.InputClosedException e) {
            System.out.println();
            System.out.println("Input closed. Goodbye.");
        }
    }

    private static void checkDatabase(ConnectionFactory connections) throws SQLException {
        try (Connection ignored = connections.getConnection()) {
            // opening and closing successfully is the whole check
        }
    }
}