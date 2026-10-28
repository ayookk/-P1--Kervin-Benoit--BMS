package com.example.banking.app;

import com.example.banking.exception.BankingException;
import com.example.banking.exception.PersistenceException;
import com.example.banking.security.SessionContext;
import com.example.banking.service.AuthService;
import java.io.PrintStream;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Menu for a logged-in customer. Account options are added in the next milestone. */
public class CustomerMenu {

    private static final Logger log = LoggerFactory.getLogger(CustomerMenu.class);

    private final ConsoleInput in;
    private final PrintStream out;
    private final AuthService auth;
    private final SessionContext session;

    public CustomerMenu(ConsoleInput in, PrintStream out, AuthService auth, SessionContext session) {
        this.in = in;
        this.out = out;
        this.auth = auth;
        this.session = session;
    }

    public void run() {
        while (session.isValid()) {
            out.println();
            out.println("===== Logged in as " + session.getUsername() + " =====");
            out.println("1. Change password");
            out.println("2. Log out");
            int choice = in.readChoice("Choose an option: ", 1, 2);
            switch (choice) {
                case 1 -> safely(this::changePassword);
                case 2 -> {
                    auth.logout(session);
                    out.println("You have been logged out.");
                }
                default -> { }
            }
        }
    }

    private void changePassword() {
        char[] current = in.readPassword("Current password: ");
        char[] next = in.readPassword("New password: ");
        char[] confirm = in.readPassword("Confirm new password: ");

        boolean match = Arrays.equals(next, confirm);
        Arrays.fill(confirm, '\0');
        if (!match) {
            Arrays.fill(current, '\0');
            Arrays.fill(next, '\0');
            out.println("New passwords do not match.");
            return;
        }
        auth.changePassword(session, current, next);
        out.println("Password changed.");
    }

    private void safely(Runnable action) {
        try {
            action.run();
        } catch (PersistenceException e) {
            log.error("Database error", e);
            out.println("The operation could not be completed. Please try again.");
        } catch (BankingException e) {
            out.println(e.getMessage());
        }
    }
}