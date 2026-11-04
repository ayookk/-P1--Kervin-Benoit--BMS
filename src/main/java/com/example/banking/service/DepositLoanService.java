package com.example.banking.service;

import com.example.banking.dao.AccountDAO;
import com.example.banking.dao.TransactionAuditDAO;
import com.example.banking.dao.TransactionDAO;
import com.example.banking.exception.AccountNotEligibleException;
import com.example.banking.exception.AuthorizationException;
import com.example.banking.exception.ValidationException;
import com.example.banking.model.Account;
import com.example.banking.model.AccountStatus;
import com.example.banking.model.AccountType;
import com.example.banking.model.BankTransaction;
import com.example.banking.model.DepositAccount;
import com.example.banking.model.LoanAccount;
import com.example.banking.model.Money;
import com.example.banking.model.TransactionType;
import com.example.banking.security.SessionContext;
import com.example.banking.util.JdbcTransaction;
import com.example.banking.util.ReferenceGenerator;
import com.example.banking.validation.AccountValidator;
import com.example.banking.validation.TransactionValidator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Fixed-term deposits and loan repayment (design doc sections 5.1 and 18.5).
 * Same recipe as a transfer: lock both rows, apply the domain rule, update, one ledger row, commit, audit.
 */
public class DepositLoanService {

    /** What opening a deposit returns: the new account and the ledger row that funded it. */
    public record DepositOpening(DepositAccount account, BankTransaction transaction) { }

    private final AccountDAO accounts;
    private final TransactionDAO ledger;
    private final TransactionAuditor auditor;
    private final JdbcTransaction tx;
    private final TransactionValidator amounts;
    private final AccountValidator terms;
    private final AccountService accountService;
    private final ReferenceGenerator refs;
    private final Clock clock;

    public DepositLoanService(AccountDAO accounts, TransactionDAO ledger, TransactionAuditDAO audit,
                              JdbcTransaction tx, TransactionValidator amounts, AccountValidator terms,
                              AccountService accountService, ReferenceGenerator refs, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.auditor = new TransactionAuditor(audit);
        this.tx = tx;
        this.amounts = amounts;
        this.terms = terms;
        this.accountService = accountService;
        this.refs = refs;
        this.clock = clock;
    }

    // ---------- open a fixed-term deposit ----------

    /** Moves money from Savings/Current into a NEW deposit account, all in one transaction. */
    public DepositOpening openDeposit(SessionContext session, long fundingAccountId, Money principal,
                                      int termMonths, BigDecimal annualRate) {
        amounts.requireValidAmount(principal);
        terms.validateDepositTerms(principal, termMonths, annualRate);
        String number = accountService.uniqueNumber(AccountType.DEPOSIT);
        String ref = refs.newTransactionReference();
        long customerId = session.getCustomerId();

        return auditor.run(session, ref, TransactionType.TRANSFER, () -> tx.executeWithRetry(conn -> {
            Account funding = accounts.lockOwned(conn, fundingAccountId, customerId)
                    .orElseThrow(AuthorizationException::new);
            requireCash(funding, "A deposit must be funded from a Savings or Current account.");

            Money fundingAfter = funding.debit(principal);                 // checks funds
            accounts.updateBalance(conn, funding.getAccountId(), fundingAfter);

            DepositAccount deposit = DepositAccount.open(customerId, number, principal, termMonths,
                    annualRate, LocalDate.now(clock));                    // balance = principal
            accounts.insert(conn, deposit);                                // accounts + deposit_details rows

            BankTransaction row = ledger.insert(conn, BankTransaction.transfer(ref, funding.getAccountId(),
                    deposit.getAccountId(), principal, fundingAfter, deposit.getBalance()));
            return new DepositOpening(deposit, row);
        }));
    }

    // ---------- settle a matured deposit ----------

    /** After maturity: pays the whole balance into Savings/Current and closes the deposit. */
    public BankTransaction settleDeposit(SessionContext session, long depositAccountId, long payoutAccountId) {
        if (depositAccountId == payoutAccountId) {
            throw new ValidationException("Choose two different accounts.");
        }
        String ref = refs.newTransactionReference();
        long customerId = session.getCustomerId();

        return auditor.run(session, ref, TransactionType.TRANSFER, () -> tx.executeWithRetry(conn -> {
            List<Account> locked = accounts.lockForUpdate(conn, List.of(depositAccountId, payoutAccountId));
            Account depositRow = pick(locked, depositAccountId);
            Account payout = pick(locked, payoutAccountId);
            requireOwned(depositRow, customerId);
            requireOwned(payout, customerId);
            if (!(depositRow instanceof DepositAccount deposit)) {
                throw new AccountNotEligibleException("Please choose a fixed-term Deposit account to settle.");
            }
            requireCash(payout, "Deposit proceeds must go to a Savings or Current account.");

            deposit.markMaturedIfDue(LocalDate.now(clock));               // lazy maturity check
            Money proceeds = deposit.settle();                             // refuses before maturity
            Money payoutAfter = payout.credit(proceeds);

            accounts.updateBalance(conn, deposit.getAccountId(), Money.ZERO);
            accounts.updateStatus(conn, deposit.getAccountId(), AccountStatus.CLOSED);
            accounts.updateBalance(conn, payout.getAccountId(), payoutAfter);
            return ledger.insert(conn, BankTransaction.transfer(ref, deposit.getAccountId(),
                    payout.getAccountId(), proceeds, Money.ZERO, payoutAfter));
        }));
    }

    // ---------- repay a loan ----------

    /** Pays from Savings/Current to reduce what's owed. Over-payment refused; closes the loan at zero. */
    public BankTransaction repayLoan(SessionContext session, long fundingAccountId, long loanAccountId,
                                     Money amount) {
        amounts.requireValidAmount(amount);
        if (fundingAccountId == loanAccountId) {
            throw new ValidationException("Choose two different accounts.");
        }
        String ref = refs.newTransactionReference();
        long customerId = session.getCustomerId();

        return auditor.run(session, ref, TransactionType.LOAN_REPAYMENT, () -> tx.executeWithRetry(conn -> {
            List<Account> locked = accounts.lockForUpdate(conn, List.of(fundingAccountId, loanAccountId));
            Account funding = pick(locked, fundingAccountId);
            Account loanRow = pick(locked, loanAccountId);
            requireOwned(funding, customerId);
            requireOwned(loanRow, customerId);
            if (!(loanRow instanceof LoanAccount loan)) {
                throw new AccountNotEligibleException("Please choose a Loan account to repay.");
            }
            requireCash(funding, "Repayments must come from a Savings or Current account.");

            Money loanAfter = loan.repay(amount);                          // refuses over-payment
            Money fundingAfter = funding.debit(amount);                    // checks funds

            accounts.updateBalance(conn, funding.getAccountId(), fundingAfter);
            accounts.updateBalance(conn, loan.getAccountId(), loanAfter);
            if (loan.getStatus() == AccountStatus.CLOSED) {
                accounts.updateStatus(conn, loan.getAccountId(), AccountStatus.CLOSED);
            }
            return ledger.insert(conn, BankTransaction.loanRepayment(ref, funding.getAccountId(),
                    loan.getAccountId(), amount, fundingAfter, loanAfter));
        }));
    }

    // ---------- helpers ----------

    private static Account pick(List<Account> locked, long accountId) {
        return locked.stream().filter(a -> a.getAccountId() == accountId).findFirst().orElse(null);
    }

    private static void requireOwned(Account account, long customerId) {
        if (account == null || account.getCustomerId() != customerId) {
            throw new AuthorizationException();
        }
    }

    private static void requireCash(Account account, String message) {
        if (account.type() != AccountType.SAVINGS && account.type() != AccountType.CURRENT) {
            throw new AccountNotEligibleException(message);
        }
    }
}