package com.example.banking.dao;

import com.example.banking.model.Account;
import com.example.banking.model.AccountStatus;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;

/** Persistence for all four account types (design doc section 16). */
public interface AccountDAO {

    /** Saves a new account (plus its deposit/loan details) in its own transaction. */
    Account insert(Account account);

    /** Same, but on a connection the caller controls, so it can be part of a bigger transaction. */
    Account insert(Connection conn, Account account);

    /** Ownership is checked IN the SQL: another customer's account id simply isn't found. */
    Optional<Account> findOwnedById(long accountId, long customerId);

    Optional<Account> findByNumber(String accountNumber);

    List<Account> listByCustomer(long customerId);

    void updateStatus(long accountId, AccountStatus status);
}