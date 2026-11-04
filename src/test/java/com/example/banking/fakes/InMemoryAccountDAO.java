package com.example.banking.fakes;

import com.example.banking.dao.AccountDAO;
import com.example.banking.model.Account;
import com.example.banking.model.AccountStatus;
import com.example.banking.model.Money;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test double for AccountDAO: accounts live in a Map. */
public class InMemoryAccountDAO implements AccountDAO {

    private final Map<Long, Account> rows = new LinkedHashMap<>();
    private long nextId = 1;
    private int statusUpdates = 0;
    private int balanceUpdates = 0;

    @Override
    public Account insert(Account account) {
        account.setAccountId(nextId++);
        rows.put(account.getAccountId(), account);
        return account;
    }

    @Override
    public Account insert(Connection conn, Account account) {
        return insert(account);
    }

    @Override
    public Optional<Account> findOwnedById(long accountId, long customerId) {
        return Optional.ofNullable(rows.get(accountId))
                .filter(a -> a.getCustomerId() == customerId);
    }

    @Override
    public Optional<Account> findByNumber(String accountNumber) {
        return rows.values().stream()
                .filter(a -> a.getAccountNumber().equals(accountNumber))
                .findFirst();
    }

    @Override
    public List<Account> listByCustomer(long customerId) {
        return new ArrayList<>(rows.values().stream()
                .filter(a -> a.getCustomerId() == customerId)
                .toList());
    }

    @Override
    public void updateStatus(long accountId, AccountStatus status) {
        statusUpdates++;
    }

    @Override
    public Optional<Account> lockOwned(Connection conn, long accountId, long customerId) {
        return findOwnedById(accountId, customerId);
    }

    @Override
    public List<Account> lockForUpdate(Connection conn, List<Long> accountIds) {
        return accountIds.stream().distinct().sorted()
                .map(rows::get)
                .filter(a -> a != null)
                .toList();
    }

    @Override
    public void updateBalance(Connection conn, long accountId, Money newBalance) {
        balanceUpdates++;   // the Account object itself already holds the new balance
    }
        @Override
    public void updateStatus(Connection conn, long accountId, AccountStatus status) {
        statusUpdates++;
    }
    public int statusUpdates() { return statusUpdates; }
    public int balanceUpdates() { return balanceUpdates; }
}