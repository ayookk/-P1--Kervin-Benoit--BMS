package com.example.banking.app;

import com.example.banking.dao.CustomerDAO;
import com.example.banking.dao.LoginAuditDAO;
import com.example.banking.dao.jdbc.JdbcCustomerDAO;
import com.example.banking.dao.jdbc.JdbcLoginAuditDAO;
import com.example.banking.security.PasswordHasher;
import com.example.banking.security.Pbkdf2PasswordHasher;
import com.example.banking.service.AuthService;
import com.example.banking.util.AppConfig;
import com.example.banking.util.ConnectionFactory;
import com.example.banking.validation.CustomerValidator;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point: builds every object once, wires them together, starts the menu. */
public class BankingApp {

    private static final Logger log = LoggerFactory.getLogger(BankingApp.class);

    public static void main(String[] args) {
        try {
            AppConfig config = AppConfig.load();
            ConnectionFactory connections = new ConnectionFactory(config);
            checkDatabase(connections);

            int queryTimeout = config.dbQueryTimeoutSeconds();
            CustomerDAO customerDao = new JdbcCustomerDAO(connections, queryTimeout);
            LoginAuditDAO loginAuditDao = new JdbcLoginAuditDAO(connections, queryTimeout);
            PasswordHasher hasher = new Pbkdf2PasswordHasher(config.passwordHashIterations());

            AuthService auth = new AuthService(customerDao, loginAuditDao, hasher, new CustomerValidator(),
                    Clock.systemUTC(), config.authMaxFailedLogins(),
                    Duration.ofMinutes(config.authLockMinutes()));

            log.info("Application started");
            new MainMenu(new ConsoleInput(System.out), System.out, auth).run();
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