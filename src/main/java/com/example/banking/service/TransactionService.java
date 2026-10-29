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
import com.example.banking.model.AuditOutcome;
import com.example.banking.model.BankTransaction;
import com.example.banking.model.Money;
import com.example.banking.model.TransactionType;
import com.example.banking.security.SessionContext;
import com.example.banking.util.JdbcTransaction;
import com.example.banking.util.ReferenceGenerator;
import com.example.banking.validation.TransactionValidator;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Money movement (design doc sections 15 and 18.3). Owns the database transaction boundary.
 * Pattern for every operation: validate -> lock owned row(s) -> domain rule -> update -> ledger -> commit -> audit.
 */
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private record AuditCode(AuditOutcome outcome, String failureCode) { }

    private final AccountDAO accounts;
    private final TransactionDAO ledger;
    private final TransactionAuditDAO audit;
    private final JdbcTransaction tx;
    private final TransactionValidator validator;
    private final ReferenceGenerator refs;

    public TransactionService(AccountDAO accounts, TransactionDAO ledger, TransactionAuditDAO audit,
                              JdbcTransaction tx, TransactionValidator validator, ReferenceGenerator refs) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.audit = audit;
        this.tx = tx;
        this.validator = validator;
        this.refs = refs;
    }

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

    /** Maps each failure to an audit outcome and a short code: never raw SQL or messages (doc section 24). */
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
            // never let an audit failure hide the real result from the user
            log.warn("Could not write transaction audit ref={}", ref, e);
        }
    }
}