package com.example.banking.service;

import com.example.banking.dao.TransactionAuditDAO;
import com.example.banking.exception.AccountNotEligibleException;
import com.example.banking.exception.AuthorizationException;
import com.example.banking.exception.BankingException;
import com.example.banking.exception.InsufficientFundsException;
import com.example.banking.exception.PersistenceException;
import com.example.banking.exception.ValidationException;
import com.example.banking.model.AuditOutcome;
import com.example.banking.model.TransactionType;
import com.example.banking.security.SessionContext;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a money operation and records the attempt in transaction_audit afterwards (doc AD-7).
 * Shared by every service that moves money, so the audit rules live in one place.
 */
public class TransactionAuditor {

    private static final Logger log = LoggerFactory.getLogger(TransactionAuditor.class);

    private record AuditCode(AuditOutcome outcome, String failureCode) { }

    private final TransactionAuditDAO audit;

    public TransactionAuditor(TransactionAuditDAO audit) {
        this.audit = audit;
    }

    /** Runs the work; audits SUCCESS, or the failure's outcome + code, then rethrows the failure. */
    public <T> T run(SessionContext session, String ref, TransactionType type, Supplier<T> work) {
        try {
            T result = work.get();
            write(session, ref, type, new AuditCode(AuditOutcome.SUCCESS, null));
            log.info("{} succeeded ref={}", type, ref);
            return result;
        } catch (BankingException e) {
            AuditCode code = classify(e);
            write(session, ref, type, code);
            log.info("{} {} ref={} code={}", type, code.outcome(), ref, code.failureCode());
            throw e;
        }
    }

    /** Short codes only: never raw SQL, messages or stack traces (doc section 24). */
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

    private void write(SessionContext session, String ref, TransactionType type, AuditCode code) {
        try {
            audit.record(session.getCustomerId(), ref, type, code.outcome(), code.failureCode());
        } catch (PersistenceException e) {
            log.warn("Could not write transaction audit ref={}", ref, e);   // never hide the real result
        }
    }
}