package com.example.banking.app;

import com.example.banking.ai.AssistantIntent;
import com.example.banking.ai.AssistantIntent.ChangePassword;
import com.example.banking.ai.AssistantIntent.CheckBalance;
import com.example.banking.ai.AssistantIntent.Deposit;
import com.example.banking.ai.AssistantIntent.NotUnderstood;
import com.example.banking.ai.AssistantIntent.ShowHistory;
import com.example.banking.ai.AssistantIntent.Transfer;
import com.example.banking.ai.AssistantIntent.Withdraw;
import com.example.banking.exception.BankingException;
import com.example.banking.exception.PersistenceException;
import com.example.banking.exception.SessionExpiredException;
import com.example.banking.exception.ValidationException;
import com.example.banking.model.Account;
import com.example.banking.model.AccountStatus;
import com.example.banking.model.AccountType;
import com.example.banking.model.BankTransaction;
import com.example.banking.model.DepositAccount;
import com.example.banking.model.LoanAccount;
import com.example.banking.model.Money;
import com.example.banking.security.SessionContext;
import com.example.banking.service.DepositLoanService;
import com.example.banking.service.HistoryPage;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
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
            out.println(" 1. Ask the AI assistant (type a request in plain English)");
            out.println(" 2. My accounts");
            out.println(" 3. Open an account");
            out.println(" 4. Deposit");
            out.println(" 5. Withdraw");
            out.println(" 6. Transfer");
            out.println(" 7. Repay a loan");
            out.println(" 8. Settle a matured deposit");
            out.println(" 9. Check balance");
            out.println("10. Transaction history");
            out.println("11. Change password");
            out.println("12. Log out");
            int choice = askChoice("Choose an option: ", 1, 12);
            switch (choice) {
                case 1 -> safely(this::askAssistant);
                case 2 -> safely(this::showAccounts);
                case 3 -> safely(this::openAccount);
                case 4 -> safely(this::deposit);
                case 5 -> safely(this::withdraw);
                case 6 -> safely(this::transfer);
                case 7 -> safely(this::repayLoan);
                case 8 -> safely(this::settleDeposit);
                case 9 -> safely(this::checkBalance);
                case 10 -> safely(this::history);
                case 11 -> safely(this::changePassword);
                case 12 -> {
                    services.auth().logout(session);
                    out.println("You have been logged out.");
                }
                default -> { }
            }
        }
    }

    // ---------- AI assistant: the model suggests, the customer confirms, the normal services do the work ----------

    private void askAssistant() {
        if (services.assistant() == null) {
            out.println("The AI assistant is turned off (assistant.enabled=false in app.properties).");
            return;
        }
        out.println();
        out.println("--- AI assistant (runs locally with Ollama; nothing leaves this computer) ---");
        out.println("Examples: \"send 50 from savings to 102202600023\", \"what's my balance?\", "
                + "\"I forgot my password\"");
        String request = ask("What would you like to do? ");
        out.println("Thinking...");

        AssistantIntent intent = services.assistant().parse(request);
        log.info("Assistant understood {}", intent.getClass().getSimpleName());   // never log the message itself

        // sealed interface: the compiler checks that every possible intent is handled here
        switch (intent) {
            case Transfer t -> assistantTransfer(t);
            case Deposit d -> assistantDeposit(d);
            case Withdraw w -> assistantWithdraw(w);
            case CheckBalance b -> showBalance(resolveAccount(b.account(), a -> true,
                    "Check which account?"));
            case ShowHistory h -> showHistory(resolveAccount(h.account(), a -> true,
                    "History for which account?"));
            case ChangePassword c -> {
                out.println("For your security, passwords are only changed on the normal screen, "
                        + "which asks for your current password.");
                changePassword();
            }
            case NotUnderstood n -> out.println(n.reason());
        }
    }

    private void assistantTransfer(Transfer t) {
        Account from = resolveAccount(t.from(), CustomerMenu::isActiveCash, "Transfer from which account?");
        if (!confirm("Transfer $" + t.amount() + " from " + from.type() + " " + from.getAccountNumber()
                + " to account " + mask(t.toAccountNumber()) + "?")) {
            return;
        }
        BankTransaction done = services.transactions().transfer(session, from.getAccountId(),
                t.toAccountNumber(), t.amount());
        out.println("Transferred $" + done.getAmount() + ". Your new balance: $" + done.getSourceBalanceAfter() + ".");
        printReceipt(done);
    }

    private void assistantDeposit(Deposit d) {
        Account into = resolveAccount(d.into(), CustomerMenu::isActiveCash, "Deposit into which account?");
        if (!confirm("Deposit $" + d.amount() + " into " + into.type() + " " + into.getAccountNumber() + "?")) {
            return;
        }
        BankTransaction done = services.transactions().deposit(session, into.getAccountId(), d.amount());
        out.println("Deposited $" + done.getAmount() + ". New balance: $" + done.getDestinationBalanceAfter() + ".");
        printReceipt(done);
    }

    private void assistantWithdraw(Withdraw w) {
        Account from = resolveAccount(w.from(), CustomerMenu::isActiveCash, "Withdraw from which account?");
        if (!confirm("Withdraw $" + w.amount() + " from " + from.type() + " " + from.getAccountNumber() + "?")) {
            return;
        }
        BankTransaction done = services.transactions().withdraw(session, from.getAccountId(), w.amount());
        out.println("Withdrew $" + done.getAmount() + ". New balance: $" + done.getSourceBalanceAfter() + ".");
        printReceipt(done);
    }

    /**
     * Finds the account the assistant meant among the customer's OWN accounts.
     * If the type is missing or more than one account matches, the customer picks from a list.
     */
    private Account resolveAccount(AccountType type, Predicate<Account> allowed, String title) {
        Predicate<Account> filter = allowed.and(a -> type == null || a.type() == type);
        List<Account> matches = services.accounts().listMyAccounts(session).stream().filter(filter).toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        String none = type == null ? "You don't have a suitable account for that."
                                   : "You don't have an active " + type + " account.";
        return chooseAccount(title, filter, none);
    }

    /** Nothing happens until the customer types y or yes. */
    private boolean confirm(String summary) {
        out.println();
        out.println(summary);
        String answer = ask("Type y to confirm, anything else to cancel: ").trim().toLowerCase(Locale.ROOT);
        if (answer.equals("y") || answer.equals("yes")) {
            return true;
        }
        out.println("Cancelled. Nothing was changed.");
        return false;
    }

    private static String mask(String accountNumber) {
        String digits = accountNumber.replaceAll("\\D", "");
        return "****" + digits.substring(Math.max(0, digits.length() - 4));
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
        out.println("4. Fixed-term deposit (funded from Savings/Current)");
        out.println("5. Back");
        int choice = askChoice("Choose a type: ", 1, 5);
        Account opened = switch (choice) {
            case 1 -> services.accounts().openSavings(session);
            case 2 -> services.accounts().openCurrent(session);
            case 3 -> openLoan();
            case 4 -> openDeposit();
            default -> null;
        };
        if (opened != null) {
            out.println("Opened " + opened.type() + " account " + opened.getAccountNumber()
                    + " with balance $" + opened.getBalance() + ".");
        }
    }

    private Account openLoan() {
        out.println("Note: this records a loan you owe. No money is paid out (simplified loan).");
        Money principal = Money.of(ask("Loan amount: $"));
        BigDecimal rate = parseDecimal(ask("Annual interest rate (%): "), "interest rate");
        int tenure = parseWholeNumber(ask("Tenure (months): "), "tenure");
        return services.accounts().openLoan(session, principal, rate, tenure);
    }

    private Account openDeposit() {
        out.println("A fixed-term deposit locks money until its maturity date. Minimum $100.00.");
        Account from = chooseAccount("Fund it from which account?", CustomerMenu::isActiveCash,
                "You need an active Savings or Current account to fund a deposit.");
        Money principal = Money.of(ask("Deposit amount: $"));
        int term = parseWholeNumber(ask("Term (months): "), "term");
        BigDecimal rate = parseDecimal(ask("Annual interest rate (%): "), "interest rate");

        DepositLoanService.DepositOpening opened =
                services.depositsLoans().openDeposit(session, from.getAccountId(), principal, term, rate);
        out.println("Matures on " + opened.account().getMaturityDate() + ". " + from.type()
                + " balance is now $" + opened.transaction().getSourceBalanceAfter() + ".");
        printReceipt(opened.transaction());
        return opened.account();
    }

    // ---------- money ----------

    private void deposit() {
        Account account = chooseAccount("Deposit into which account?");
        Money amount = Money.of(ask("Amount to deposit: $"));
        BankTransaction t = services.transactions().deposit(session, account.getAccountId(), amount);
        out.println("Deposited $" + t.getAmount() + ". New balance: $" + t.getDestinationBalanceAfter() + ".");
        printReceipt(t);
    }

    private void withdraw() {
        Account account = chooseAccount("Withdraw from which account?");
        Money amount = Money.of(ask("Amount to withdraw: $"));
        BankTransaction t = services.transactions().withdraw(session, account.getAccountId(), amount);
        out.println("Withdrew $" + t.getAmount() + ". New balance: $" + t.getSourceBalanceAfter() + ".");
        printReceipt(t);
    }

    private void transfer() {
        Account from = chooseAccount("Transfer from which account?");
        String to = ask("Destination account number (12 digits): ");
        Money amount = Money.of(ask("Amount to transfer: $"));
        BankTransaction t = services.transactions().transfer(session, from.getAccountId(), to, amount);
        out.println("Transferred $" + t.getAmount() + " to account " + mask(to)
                + ". Your new balance: $" + t.getSourceBalanceAfter() + ".");
        printReceipt(t);
    }

    private void repayLoan() {
        Account loan = chooseAccount("Repay which loan?",
                a -> a instanceof LoanAccount && a.isActive(), "You have no active loans.");
        Account from = chooseAccount("Pay from which account?", CustomerMenu::isActiveCash,
                "You need an active Savings or Current account to repay a loan.");
        Money amount = Money.of(ask("Repayment amount (owed: $" + loan.getBalance() + "): $"));

        BankTransaction t = services.depositsLoans().repayLoan(session, from.getAccountId(),
                loan.getAccountId(), amount);
        out.println("Paid $" + t.getAmount() + ". Loan balance now $" + t.getDestinationBalanceAfter()
                + (t.getDestinationBalanceAfter().isZero() ? " - fully repaid, loan closed." : "."));
        printReceipt(t);
    }

    private void settleDeposit() {
        Account deposit = chooseAccount("Settle which deposit?",
                a -> a instanceof DepositAccount && a.getStatus() != AccountStatus.CLOSED,
                "You have no open fixed-term deposits.");
        Account to = chooseAccount("Pay the proceeds into which account?", CustomerMenu::isActiveCash,
                "You need an active Savings or Current account to receive the proceeds.");

        BankTransaction t = services.depositsLoans().settleDeposit(session, deposit.getAccountId(),
                to.getAccountId());
        out.println("Deposit settled: $" + t.getAmount() + " paid out. " + to.type() + " balance is now $"
                + t.getDestinationBalanceAfter() + ". The deposit is now closed.");
        printReceipt(t);
    }

    private void checkBalance() {
        showBalance(chooseAccount("Check which account?"));
    }

    private void showBalance(Account chosen) {
        Account fresh = services.accounts().getOwnedAccount(session, chosen.getAccountId());
        out.println(fresh.type() + " " + fresh.getAccountNumber() + ": $" + fresh.getBalance()
                + " (" + fresh.getStatus() + ")" + (fresh instanceof LoanAccount ? " - amount owed" : ""));
    }

    private void history() {
        showHistory(chooseAccount("History for which account?"));
    }

    private void showHistory(Account account) {
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
            String nav = ask(prompt).toUpperCase(Locale.ROOT);
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

    // ---------- choosing accounts ----------

    private Account chooseAccount(String title) {
        return chooseAccount(title, a -> true, "You have no accounts yet. Choose 'Open an account' first.");
    }

    /** Numbered list of the customer's OWN accounts that pass the filter; they pick by number, never by id. */
    private Account chooseAccount(String title, Predicate<Account> filter, String noneMessage) {
        List<Account> options = services.accounts().listMyAccounts(session).stream().filter(filter).toList();
        if (options.isEmpty()) {
            throw new ValidationException(noneMessage);
        }
        out.println();
        out.println(title);
        for (int i = 0; i < options.size(); i++) {
            Account a = options.get(i);
            out.printf("%d. %-8s %s  $%s%n", i + 1, a.type(), a.getAccountNumber(), a.getBalance());
        }
        int choice = askChoice("Choose account: ", 1, options.size());
        return options.get(choice - 1);
    }

    private static boolean isActiveCash(Account a) {
        return (a.type() == AccountType.SAVINGS || a.type() == AccountType.CURRENT) && a.isActive();
    }

    private void printReceipt(BankTransaction t) {
        out.println("Reference: " + t.getReferenceNumber() + "   Time: " + TIME.format(t.getCreatedAt()));
    }

    // ---------- password ----------

    private void changePassword() {
        char[] current = askPassword("Current password: ");
        char[] next = askPassword("New password: ");
        char[] confirm = askPassword("Confirm new password: ");

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

    // ---------- every input goes through these: expiry is checked the moment input returns (doc 20) ----------

    private String ask(String prompt) {
        String answer = in.readLine(prompt);
        services.sessions().requireActive(session);
        return answer;
    }

    private int askChoice(String prompt, int min, int max) {
        int choice = in.readChoice(prompt, min, max);
        services.sessions().requireActive(session);
        return choice;
    }

    private char[] askPassword(String prompt) {
        char[] password = in.readPassword(prompt);
        try {
            services.sessions().requireActive(session);
        } catch (SessionExpiredException e) {
            Arrays.fill(password, '\0');
            throw e;
        }
        return password;
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