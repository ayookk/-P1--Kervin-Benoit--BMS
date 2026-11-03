package com.example.banking.app;

import com.example.banking.exception.BankingException;
import com.example.banking.exception.PersistenceException;
import com.example.banking.exception.ValidationException;
import com.example.banking.model.Account;
import com.example.banking.model.BankTransaction;
import com.example.banking.model.DepositAccount;
import com.example.banking.model.LoanAccount;
import com.example.banking.model.Money;
import com.example.banking.security.SessionContext;
import com.example.banking.service.HistoryPage;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Menu for a logged-in customer. Reads input, calls services, prints results. No rules here. */
public class CustomerMenu {

    private static final Logger log = LoggerFactory.getLogger(CustomerMenu.class);
    private static final String ROW = "%-3s %-14s %-9s %15s  %-8s %s%n";
    private static final String HIST_ROW = "%-19s %-14s %12s %12s  %-7s %s%n";
    private static final int HISTORY_PAGE_SIZE = 5;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ConsoleInput in;
    private final PrintStream out;
    private final AppServices services;
    private final SessionContext session;

    public CustomerMenu(ConsoleInput in, PrintStream out, AppServices services, SessionContext session) {
        this.in = in;
        this.out = out;
        this.services = services;
        this.session = session;
    }

    public void run() {
        while (session.isValid()) {
            out.println();
            out.println("===== Logged in as " + session.getUsername() + " =====");
            out.println("1. My accounts");
            out.println("2. Open an account");
            out.println("3. Deposit");
            out.println("4. Withdraw");
            out.println("5. Transfer");
            out.println("6. Check balance");
            out.println("7. Transaction history");
            out.println("8. Change password");
            out.println("9. Log out");
            int choice = in.readChoice("Choose an option: ", 1, 9);
            switch (choice) {
                case 1 -> safely(this::showAccounts);
                case 2 -> safely(this::openAccount);
                case 3 -> safely(this::deposit);
                case 4 -> safely(this::withdraw);
                case 5 -> safely(this::transfer);
                case 6 -> safely(this::checkBalance);
                case 7 -> safely(this::history);
                case 8 -> safely(this::changePassword);
                case 9 -> {
                    services.auth().logout(session);
                    out.println("You have been logged out.");
                }
                default -> { }
            }
        }
    }

    // ---------- accounts ----------

    private void showAccounts() {
        List<Account> mine = services.accounts().listMyAccounts(session);
        out.println();
        if (mine.isEmpty()) {
            out.println("You have no accounts yet. Choose 'Open an account'.");
            return;
        }
        out.printf(ROW, "#", "Account number", "Type", "Balance", "Status", "");
        for (int i = 0; i < mine.size(); i++) {
            Account a = mine.get(i);
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
            case 1 -> services.accounts().openSavings(session);
            case 2 -> services.accounts().openCurrent(session);
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
        return services.accounts().openLoan(session, principal, rate, tenure);
    }

    // ---------- money ----------

    private void deposit() {
        Account account = chooseAccount("Deposit into which account?");
        Money amount = Money.of(in.readLine("Amount to deposit: $"));
        BankTransaction t = services.transactions().deposit(session, account.getAccountId(), amount);
        out.println("Deposited $" + t.getAmount() + ". New balance: $" + t.getDestinationBalanceAfter() + ".");
        printReceipt(t);
    }

    private void withdraw() {
        Account account = chooseAccount("Withdraw from which account?");
        Money amount = Money.of(in.readLine("Amount to withdraw: $"));
        BankTransaction t = services.transactions().withdraw(session, account.getAccountId(), amount);
        out.println("Withdrew $" + t.getAmount() + ". New balance: $" + t.getSourceBalanceAfter() + ".");
        printReceipt(t);
    }

    private void transfer() {
        Account from = chooseAccount("Transfer from which account?");
        String to = in.readLine("Destination account number (12 digits): ");
        Money amount = Money.of(in.readLine("Amount to transfer: $"));
        BankTransaction t = services.transactions().transfer(session, from.getAccountId(), to, amount);
        String digits = to.replaceAll("\\D", "");
        String masked = "****" + digits.substring(Math.max(0, digits.length() - 4));
        out.println("Transferred $" + t.getAmount() + " to account " + masked
                + ". Your new balance: $" + t.getSourceBalanceAfter() + ".");
        printReceipt(t);
    }

    private void checkBalance() {
        Account chosen = chooseAccount("Check which account?");
        Account fresh = services.accounts().getOwnedAccount(session, chosen.getAccountId());
        out.println(fresh.type() + " " + fresh.getAccountNumber() + ": $" + fresh.getBalance()
                + " (" + fresh.getStatus() + ")" + (fresh instanceof LoanAccount ? " - amount owed" : ""));
    }

    private void history() {
        Account account = chooseAccount("History for which account?");
        int page = 0;
        while (true) {
            HistoryPage p = services.transactions().history(session, account.getAccountId(), page, HISTORY_PAGE_SIZE);
            out.println();
            out.println("History for " + account.type() + " " + account.getAccountNumber());
            if (p.totalItems() == 0) {
                out.println("No transactions yet.");
                return;
            }
            out.printf(HIST_ROW, "Time", "Type", "Amount", "Balance", "Status", "Reference");
            for (BankTransaction t : p.items()) {
                boolean credit = t.directionFor(account.getAccountId()) == BankTransaction.Direction.CREDIT;
                Money after = credit ? t.getDestinationBalanceAfter() : t.getSourceBalanceAfter();
                out.printf(HIST_ROW, TIME.format(t.getCreatedAt()), t.getType(),
                        (credit ? "+$" : "-$") + t.getAmount(), after == null ? "" : "$" + after,
                        t.getStatus(), t.getReferenceNumber());
            }
            out.println("Page " + (p.page() + 1) + " of " + p.totalPages() + "  (" + p.totalItems() + " transactions)");

            String prompt = (p.hasNext() ? "[N]ext  " : "") + (p.hasPrevious() ? "[P]revious  " : "") + "[B]ack: ";
            String nav = in.readLine(prompt).toUpperCase();
            if (nav.equals("N") && p.hasNext()) {
                page++;
            } else if (nav.equals("P") && p.hasPrevious()) {
                page--;
            } else if (nav.equals("B")) {
                return;
            } else {
                out.println("Please type one of the letters shown.");
            }
        }
    }

    /** Shows the customer's own accounts as a numbered list; they pick by number, never by typing an id. */
    private Account chooseAccount(String title) {
        List<Account> mine = services.accounts().listMyAccounts(session);
        if (mine.isEmpty()) {
            throw new ValidationException("You have no accounts yet. Choose 'Open an account' first.");
        }
        out.println();
        out.println(title);
        for (int i = 0; i < mine.size(); i++) {
            Account a = mine.get(i);
            out.printf("%d. %-8s %s  $%s%n", i + 1, a.type(), a.getAccountNumber(), a.getBalance());
        }
        int choice = in.readChoice("Choose account: ", 1, mine.size());
        return mine.get(choice - 1);
    }

    private void printReceipt(BankTransaction t) {
        out.println("Reference: " + t.getReferenceNumber() + "   Time: " + TIME.format(t.getCreatedAt()));
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
        services.auth().changePassword(session, current, next);
        out.println("Password changed.");
    }

    // ---------- input parsing helpers ----------

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