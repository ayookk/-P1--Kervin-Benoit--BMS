package com.example.banking.model;

import com.example.banking.exception.AccountNotEligibleException;
import com.example.banking.exception.ValidationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public class LoanAccount extends Account {

    private final Money principal;
    private final BigDecimal annualRate;
    private final int tenureMonths;
    private final LocalDate startDate;

    public LoanAccount(Long accountId, Long customerId, String accountNumber, Money balance,
                       AccountStatus status, Instant createdAt, Money principal,
                       BigDecimal annualRate, int tenureMonths, LocalDate startDate) {
        super(accountId, customerId, accountNumber, balance, status, createdAt);
        this.principal = principal;
        this.annualRate = annualRate;
        this.tenureMonths = tenureMonths;
        this.startDate = startDate;
    }

    /** Creates a loan record. Balance starts at the full principal (amount owed). */
    public static LoanAccount open(Long customerId, String accountNumber, Money principal,
                                   BigDecimal annualRate, int tenureMonths, LocalDate today) {
        requirePositive(principal);
        if (tenureMonths <= 0) {
            throw new ValidationException("Tenure must be at least 1 month.");
        }
        return new LoanAccount(null, customerId, accountNumber, principal, AccountStatus.ACTIVE,
                null, principal, annualRate, tenureMonths, today);
    }

    @Override public AccountType type()  { return AccountType.LOAN; }
    @Override public boolean canDebit()  { return false; }   // a loan is never spendable cash
    @Override public boolean canCredit() { return false; }   // reduced only through repay()

    /** Reduces the amount owed. Over-payment refused; closes the loan at zero. Returns what's left. */
    public Money repay(Money amount) {
        requirePositive(amount);
        if (!isActive()) {
            throw new AccountNotEligibleException("This loan is not active.");
        }
        if (amount.isGreaterThan(getBalance())) {
            throw new ValidationException(
                    "Repayment exceeds the outstanding balance of $" + getBalance() + ".");
        }
        setBalanceInternal(getBalance().subtract(amount));
        if (getBalance().isZero()) {
            setStatusInternal(AccountStatus.CLOSED);
        }
        return getBalance();
    }

    public Money getPrincipal() { return principal; }
    public BigDecimal getAnnualRate() { return annualRate; }
    public int getTenureMonths() { return tenureMonths; }
    public LocalDate getStartDate() { return startDate; }
}