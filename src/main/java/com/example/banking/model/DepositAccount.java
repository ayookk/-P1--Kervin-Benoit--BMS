package com.example.banking.model;

import com.example.banking.exception.AccountNotEligibleException;
import com.example.banking.exception.ValidationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public class DepositAccount extends Account {

    private final Money principal;
    private final int termMonths;
    private final BigDecimal interestRate;
    private final LocalDate maturityDate;

    public DepositAccount(Long accountId, Long customerId, String accountNumber, Money balance,
                          AccountStatus status, Instant createdAt, Money principal,
                          int termMonths, BigDecimal interestRate, LocalDate maturityDate) {
        super(accountId, customerId, accountNumber, balance, status, createdAt);
        this.principal = principal;
        this.termMonths = termMonths;
        this.interestRate = interestRate;
        this.maturityDate = maturityDate;
    }

    /** Opens a term deposit. Balance = principal; maturity date is fixed at creation. */
    public static DepositAccount open(Long customerId, String accountNumber, Money principal,
                                      int termMonths, BigDecimal interestRate, LocalDate today) {
        requirePositive(principal);
        if (termMonths <= 0) {
            throw new ValidationException("Term must be at least 1 month.");
        }
        return new DepositAccount(null, customerId, accountNumber, principal, AccountStatus.ACTIVE,
                null, principal, termMonths, interestRate, today.plusMonths(termMonths));
    }

    @Override public AccountType type()  { return AccountType.DEPOSIT; }
    @Override public boolean canDebit()  { return getStatus() == AccountStatus.MATURED; }
    @Override public boolean canCredit() { return false; }   // only the opening deposit

    /** Lazy maturity check: called on login/listing. Returns true if the status changed. */
    public boolean markMaturedIfDue(LocalDate today) {
        if (isActive() && !today.isBefore(maturityDate)) {
            setStatusInternal(AccountStatus.MATURED);
            return true;
        }
        return false;
    }

    /** Releases the full balance after maturity and closes the account. Returns the payout. */
    public Money settle() {
        if (!canDebit()) {
            throw new AccountNotEligibleException(
                    "This deposit can only be withdrawn after it matures on " + maturityDate + ".");
        }
        Money payout = getBalance();
        debit(payout);
        setStatusInternal(AccountStatus.CLOSED);
        return payout;
    }

    public Money getPrincipal() { return principal; }
    public int getTermMonths() { return termMonths; }
    public BigDecimal getInterestRate() { return interestRate; }
    public LocalDate getMaturityDate() { return maturityDate; }
}