package com.example.banking.app;

import com.example.banking.exception.BankingException;
import com.example.banking.exception.PersistenceException;
import com.example.banking.model.Customer;
import com.example.banking.security.SessionContext;
import com.example.banking.service.AuthService;
import com.example.banking.service.RegisterRequest;
import java.io.PrintStream;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Welcome screen. Contains no SQL and no business rules (design doc section 13). */
public class MainMenu {

    private static final Logger log = LoggerFactory.getLogger(MainMenu.class);

    private final ConsoleInput in;
    private final PrintStream out;
    private final AuthService auth;

    public MainMenu(ConsoleInput in, PrintStream out, AuthService auth) {
        this.in = in;
        this.out = out;
        this.auth = auth;
    }

    public void run() {
        while (true) {
            out.println();
            out.println("===== Secure Banking CLI =====");
            out.println("1. Register");
            out.println("2. Log in");
            out.println("3. Exit");
            int choice = in.readChoice("Choose an option: ", 1, 3);
            switch (choice) {
                case 1 -> safely(this::register);
                case 2 -> safely(this::login);
                case 3 -> {
                    out.println("Goodbye.");
                    return;
                }
                default -> { }   // readChoice guarantees 1-3
            }
        }
    }

    private void register() {
        out.println();
        out.println("--- Register ---");
        out.println("Password: at least 10 characters, with 3 of: lowercase, uppercase, digit, symbol.");
        String fullName = in.readLine("Full name: ");
        String email = in.readLine("Email: ");
        String phone = in.readLine("Phone (optional, press Enter to skip): ");
        String username = in.readLine("Username: ");
        char[] password = in.readPassword("Password: ");
        char[] confirm = in.readPassword("Confirm password: ");

        boolean match = Arrays.equals(password, confirm);
        Arrays.fill(confirm, '\0');
        if (!match) {
            Arrays.fill(password, '\0');
            out.println("Passwords do not match. Please try again.");
            return;
        }

        Customer created = auth.register(new RegisterRequest(fullName, email, phone, username, password));
        out.println("Registration successful. You can now log in as '" + created.getUsername() + "'.");
    }

    private void login() {
        out.println();
        out.println("--- Log in ---");
        String username = in.readLine("Username: ");
        char[] password = in.readPassword("Password: ");
        SessionContext session = auth.authenticate(username, password);
        out.println("Welcome, " + session.getUsername() + "!");
        new CustomerMenu(in, out, auth, session).run();
    }

    /** Runs an action and turns any banking error into a safe message instead of a crash. */
    private void safely(Runnable action) {
        try {
            action.run();
        } catch (PersistenceException e) {
            log.error("Database error", e);   // full details go to logs/banking-cli.log only
            out.println("The operation could not be completed. Please try again.");
        } catch (BankingException e) {
            out.println(e.getMessage());
        }
    }
}