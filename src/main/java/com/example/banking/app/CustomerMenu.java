package com.example.banking.app;

import com.example.banking.exception.BankingException;
import com.example.banking.exception.PersistenceException;
import com.example.banking.exception.ValidationException;
import com.example.banking.model.Account;
import com.example.banking.model.DepositAccount;
import com.example.banking.model.LoanAccount;
import com.example.banking.model.Money;
import com.example.banking.security.SessionContext;
import com.example.banking.service.AccountService;
import com.example.banking.service.AuthService;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Menu for a logged-in customer. Reads input, calls services, prints results. No rules here. */
public class CustomerMenu {

    private static final Logger log = LoggerFactory.getLogger(CustomerMenu.class);
    private static final String ROW = "%-3s %-14s %-9s %15s  %-8s %s%n";

    private final ConsoleInput in;
    private final PrintStream out;
    private final AuthService auth;
    private final AccountService accounts;
    private final SessionContext session;

    public CustomerMenu(ConsoleInput in, PrintStream out, AuthService auth,
                        AccountService accounts, SessionContext session) {
        this.in = in;
        this.out = out;
        this.auth = auth;
        this.accounts = accounts;
        this.session = session;
    }

    public void run() {
        while (session.isValid()) {
            out.println();
            out.println("===== Logged in as " + session.getUsername() + " =====");
            out.println("1. My accounts");
            out.println("2. Open an account");
            out.println("3. Change password");
            out.println("4. Log out");
            int choice = in.readChoice("Choose an option: ", 1, 4);
            switch (choice) {
                case 1 -> safely(this::showAccounts);
                case 2 -> safely(this::openAccount);
                case 3 -> safely(this::changePassword);
                case 4 -> {
                    auth.logout(session);
                    out.println("You have been logged out.");
                }
                default -> { }
            }
        }
    }

    // ---------- accounts ----------

    private void showAccounts() {
        List<Account> mine = accounts.listMyAccounts(session);
        out.println();
        if (mine.isEmpty()) {
            out.println("You have no accounts yet. Choose 'Open an account'.");
            return;
        }
        out.printf(ROW, "#", "Account number", "Type", "Balance", "Status", "");
        for (int i = 0; i < mine.size(); i++) {
            Account a = mine.get(i);
            // pattern-matching switch (Java 21+): picks a note by subclass
            String note = switch (a) {
                case DepositAccount d -> "matures " + d.getMaturityDate();
                case LoanAccount l -> "amount owed";
                default -> "";
            };
            out.printf(ROW, i + 1, a.getAccountNumber(), a.type(), "$" + a.getBalance(), a.getStatus(), note);
        }
    }

    private void openAccount() {
        out.println();
        out.println("--- Open an account ---");
        out.println("1. Savings");
        out.println("2. Current");
        out.println("3. Loan");
        out.println("4. Back");
        int choice = in.readChoice("Choose a type: ", 1, 4);
        Account opened = switch (choice) {
            case 1 -> accounts.openSavings(session);
            case 2 -> accounts.openCurrent(session);
            case 3 -> openLoan();
            default -> null;
        };
        if (opened != null) {
            out.println("Opened " + opened.type() + " account " + opened.getAccountNumber()
                    + " with balance $" + opened.getBalance() + ".");
        }
    }

    private Account openLoan() {
        out.println("Note: this records a loan you owe. No money is paid out (simplified loan).");
        Money principal = Money.of(in.readLine("Loan amount: $"));
        BigDecimal rate = parseDecimal(in.readLine("Annual interest rate (%): "), "interest rate");
        int tenure = parseWholeNumber(in.readLine("Tenure (months): "), "tenure");
        return accounts.openLoan(session, principal, rate, tenure);
    }

    // ---------- password ----------

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

    // ---------- input parsing helpers (turning text into numbers is the menu's job) ----------

    private static BigDecimal parseDecimal(String text, String field) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            throw new ValidationException("Please enter a valid number for " + field + ".");
        }
    }

    private static int parseWholeNumber(String text, String field) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new ValidationException("Please enter a whole number for " + field + ".");
        }
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