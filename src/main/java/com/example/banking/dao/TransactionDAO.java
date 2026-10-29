package com.example.banking.dao;

import com.example.banking.model.BankTransaction;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;

/** The ledger: one row per COMPLETED money movement (doc AD-6, AD-7). */
public interface TransactionDAO {

    /** Inserted inside the money transaction, so it commits or rolls back with the balance change. */
    BankTransaction insert(Connection conn, BankTransaction tx);

    Optional<BankTransaction> findByReference(String referenceNumber);

    /** Newest first, stable order (doc section 18.6). */
    List<BankTransaction> pageByAccount(long accountId, int limit, int offset);

    int countByAccount(long accountId);
}