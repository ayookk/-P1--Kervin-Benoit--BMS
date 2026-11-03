package com.example.banking.service;

import com.example.banking.dao.AccountDAO;
import com.example.banking.dao.TransactionAuditDAO;
import com.example.banking.dao.TransactionDAO;
import com.example.banking.exception.AccountNotEligibleException;
import com.example.banking.exception.AuthorizationException;
import com.example.banking.exception.BankingException;
import com.example.banking.exception.InsufficientFundsException;
import com.example.banking.exception.PersistenceException;
import com.example.banking.exception.ValidationException;
import com.example.banking.model.Account;
import com.example.banking.model.AccountType;
import com.example.banking.model.AuditOutcome;
import com.example.banking.model.BankTransaction;
import com.example.banking.model.Money;
import com.example.banking.model.TransactionType;
import com.example.banking.security.SessionContext;
import com.example.banking.util.JdbcTransaction;
import com.example.banking.util.ReferenceGenerator;
import com.example.banking.validation.TransactionValidator;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Money movement and history (design doc sections 15, 18.3, 18.4, 18.6). Owns the database transaction boundary.
 * Pattern for every operation: validate -> lock owned row(s) -> domain rule -> update -> ledger -> commit -> audit.
 */
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);
    private static final String BAD_DESTINATION = "The destination account can't receive transfers.";

    private record AuditCode(AuditOutcome outcome, String failureCode) { }

    private final AccountDAO accounts;
    private final TransactionDAO ledger;
    private final TransactionAuditDAO audit;
    private final JdbcTransaction tx;
    private final TransactionValidator validator;
    private final ReferenceGenerator refs;
    private final int maxPageSize;

    public TransactionService(AccountDAO accounts, TransactionDAO ledger, TransactionAuditDAO audit,
                              JdbcTransaction tx, TransactionValidator validator, ReferenceGenerator refs,
                              int maxPageSize) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.audit = audit;
        this.tx = tx;
        this.validator = validator;
        this.refs = refs;
        this.maxPageSize = maxPageSize;
    }

    // ---------- deposit / withdraw ----------

    public BankTransaction deposit(SessionContext session, long accountId, Money amount) {
        validator.requireValidAmount(amount);
        String ref = refs.newTransactionReference();
        return audited(session, ref, TransactionType.DEPOSIT, () -> tx.executeWithRetry(conn -> {
            Account account = accounts.lockOwned(conn, accountId, session.getCustomerId())
                    .orElseThrow(AuthorizationException::new);
            Money after = account.credit(amount);                // rule: ACTIVE Savings/Current only
            accounts.updateBalance(conn, account.getAccountId(), after);
            return ledger.insert(conn, BankTransaction.deposit(ref, account.getAccountId(), amount, after));
        }));
    }

    public BankTransaction withdraw(SessionContext session, long accountId, Money amount) {
        validator.requireValidAmount(amount);
        String ref = refs.newTransactionReference();
        return audited(session, ref, TransactionType.WITHDRAWAL, () -> tx.executeWithRetry(conn -> {
            Account account = accounts.lockOwned(conn, accountId, session.getCustomerId())
                    .orElseThrow(AuthorizationException::new);
            // balance was re-read UNDER the lock, so this funds check can't be raced
            Money after = account.debit(amount);                 // rule: ACTIVE + sufficient funds
            accounts.updateBalance(conn, account.getAccountId(), after);
            return ledger.insert(conn, BankTransaction.withdrawal(ref, account.getAccountId(), amount, after));
        }));
    }

    // ---------- transfer ----------

    /** Debit + credit + ONE ledger row in a single transaction, or nothing at all (doc F08, INV-05). */
    public BankTransaction transfer(SessionContext session, long sourceAccountId,
                                    String destinationNumber, Money amount) {
        validator.requireValidAmount(amount);
        String dstNumber = destinationNumber == null ? "" : destinationNumber.replaceAll("[\\s-]", "");
        if (!ReferenceGenerator.isValidAccountNumber(dstNumber)) {
            throw new ValidationException("That account number is not valid. Please check it and try again.");
        }
        String ref = refs.newTransactionReference();

        return audited(session, ref, TransactionType.TRANSFER, () -> tx.executeWithRetry(conn -> {
            // 1. resolve the destination id (no lock yet)
            Account destination = accounts.findByNumber(dstNumber)
                    .orElseThrow(() -> new AccountNotEligibleException(BAD_DESTINATION));
            if (destination.getAccountId() == sourceAccountId) {
                throw new ValidationException("You can't transfer to the same account.");
            }

            // 2. lock BOTH rows in ascending id order, then re-read them under the lock
            List<Account> locked = accounts.lockForUpdate(conn,
                    List.of(sourceAccountId, destination.getAccountId()));
            Account src = pick(locked, sourceAccountId);
            Account dst = pick(locked, destination.getAccountId());

            // 3. rules
            if (src == null || src.getCustomerId() != session.getCustomerId()) {
                throw new AuthorizationException();             // source must be MINE
            }
            if (dst == null || !isCashAccount(dst)) {
                throw new AccountNotEligibleException(BAD_DESTINATION);
            }
            if (!isCashAccount(src)) {
                throw new AccountNotEligibleException(
                        "Transfers can only be made from a Savings or Current account.");
            }

            // 4. move the money (debit checks ACTIVE + funds; credit checks ACTIVE)
            Money srcAfter = src.debit(amount);
            Money dstAfter = dst.credit(amount);
            accounts.updateBalance(conn, src.getAccountId(), srcAfter);
            accounts.updateBalance(conn, dst.getAccountId(), dstAfter);

            // 5. one ledger row for the whole business transaction (doc AD-6)
            return ledger.insert(conn, BankTransaction.transfer(ref, src.getAccountId(), dst.getAccountId(),
                    amount, srcAfter, dstAfter));
        }));
    }

    // ---------- history ----------

    /** One page of the account's history, newest first (doc section 18.6). Page numbers start at 0. */
    public HistoryPage history(SessionContext session, long accountId, int page, int pageSize) {
        if (page < 0) {
            throw new ValidationException("Page number can't be negative.");
        }
        if (pageSize < 1 || pageSize > maxPageSize) {
            throw new ValidationException("Page size must be 1 to " + maxPageSize + ".");
        }
        accounts.findOwnedById(accountId, session.getCustomerId())
                .orElseThrow(AuthorizationException::new);       // ownership first

        int total = ledger.countByAccount(accountId);
        if (page > 0 && (long) page * pageSize >= total) {
            throw new ValidationException("There are no more pages.");
        }
        List<BankTransaction> items = ledger.pageByAccount(accountId, pageSize, page * pageSize);
        return new HistoryPage(items, page, pageSize, total);
    }

    // ---------- helpers ----------

    private static Account pick(List<Account> locked, long accountId) {
        return locked.stream().filter(a -> a.getAccountId() == accountId).findFirst().orElse(null);
    }

    /** Deposit and Loan accounts are not normal transfer endpoints (doc section 5.1). */
    private static boolean isCashAccount(Account a) {
        return a.type() == AccountType.SAVINGS || a.type() == AccountType.CURRENT;
    }

    // ---------- audit: written AFTER commit or rollback, on its own connection (doc AD-7) ----------

    private BankTransaction audited(SessionContext session, String ref, TransactionType type,
                                    Supplier<BankTransaction> work) {
        try {
            BankTransaction done = work.get();
            writeAudit(session, ref, type, new AuditCode(AuditOutcome.SUCCESS, null));
            log.info("{} succeeded ref={}", type, ref);
            return done;
        } catch (BankingException e) {
            AuditCode code = classify(e);
            writeAudit(session, ref, type, code);
            log.info("{} {} ref={} code={}", type, code.outcome(), ref, code.failureCode());
            throw e;
        }
    }

    private static AuditCode classify(BankingException e) {
        return switch (e) {
            case InsufficientFundsException _ -> new AuditCode(AuditOutcome.REJECTED, "INSUFFICIENT_FUNDS");
            case AuthorizationException _ -> new AuditCode(AuditOutcome.REJECTED, "NOT_OWNER");
            case AccountNotEligibleException _ -> new AuditCode(AuditOutcome.REJECTED, "NOT_ELIGIBLE");
            case ValidationException _ -> new AuditCode(AuditOutcome.REJECTED, "INVALID_INPUT");
            case PersistenceException _ -> new AuditCode(AuditOutcome.ROLLED_BACK, "DB_ERROR");
            default -> new AuditCode(AuditOutcome.FAILED, "UNEXPECTED");
        };
    }

    private void writeAudit(SessionContext session, String ref, TransactionType type, AuditCode code) {
        try {
            audit.record(session.getCustomerId(), ref, type, code.outcome(), code.failureCode());
        } catch (PersistenceException e) {
            log.warn("Could not write transaction audit ref={}", ref, e);
        }
    }
}