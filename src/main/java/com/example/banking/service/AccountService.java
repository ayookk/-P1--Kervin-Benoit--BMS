package com.example.banking.service;

import com.example.banking.dao.AccountDAO;
import com.example.banking.exception.AuthorizationException;
import com.example.banking.exception.PersistenceException;
import com.example.banking.model.Account;
import com.example.banking.model.AccountStatus;
import com.example.banking.model.AccountType;
import com.example.banking.model.CurrentAccount;
import com.example.banking.model.DepositAccount;
import com.example.banking.model.LoanAccount;
import com.example.banking.model.Money;
import com.example.banking.model.SavingsAccount;
import com.example.banking.security.SessionContext;
import com.example.banking.util.ReferenceGenerator;
import com.example.banking.validation.AccountValidator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Opening and listing accounts (design doc section 15).
 * Every method takes the SessionContext: the customer id ALWAYS comes from the session,
 * never from something the user typed (doc section 1, "authorize on every call").
 */
public class AccountService {

    private static final int NUMBER_ATTEMPTS = 5;

    private final AccountDAO accounts;
    private final ReferenceGenerator refs;
    private final AccountValidator validator;
    private final Clock clock;

    public AccountService(AccountDAO accounts, ReferenceGenerator refs, AccountValidator validator, Clock clock) {
        this.accounts = accounts;
        this.refs = refs;
        this.validator = validator;
        this.clock = clock;
    }

    public Account openSavings(SessionContext session) {
        return accounts.insert(SavingsAccount.open(session.getCustomerId(), uniqueNumber(AccountType.SAVINGS)));
    }

    public Account openCurrent(SessionContext session) {
        return accounts.insert(CurrentAccount.open(session.getCustomerId(), uniqueNumber(AccountType.CURRENT)));
    }

    /** Simplified loan (doc section 5.1): records the amount owed; no money is paid out. */
    public Account openLoan(SessionContext session, Money principal, BigDecimal annualRate, int tenureMonths) {
        validator.validateLoanTerms(principal, annualRate, tenureMonths);
        LoanAccount loan = LoanAccount.open(session.getCustomerId(), uniqueNumber(AccountType.LOAN),
                principal, annualRate, tenureMonths, LocalDate.now(clock));
        return accounts.insert(loan);
    }

    /** The customer's own accounts. Also marks deposits as MATURED once their date has passed (doc 18.5). */
    public List<Account> listMyAccounts(SessionContext session) {
        List<Account> mine = accounts.listByCustomer(session.getCustomerId());
        LocalDate today = LocalDate.now(clock);
        for (Account a : mine) {
            if (a instanceof DepositAccount dep && dep.markMaturedIfDue(today)) {
                accounts.updateStatus(dep.getAccountId(), AccountStatus.MATURED);
            }
        }
        return mine;
    }

    /** Someone else's account id gives the same "Account not found." as a non-existent one. */
    public Account getOwnedAccount(SessionContext session, long accountId) {
        return accounts.findOwnedById(accountId, session.getCustomerId())
                .orElseThrow(AuthorizationException::new);
    }

    String uniqueNumber(AccountType type) {
        for (int i = 0; i < NUMBER_ATTEMPTS; i++) {
            String candidate = refs.newAccountNumber(type);
            if (accounts.findByNumber(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new PersistenceException("Could not generate a unique account number", null);
    }
}