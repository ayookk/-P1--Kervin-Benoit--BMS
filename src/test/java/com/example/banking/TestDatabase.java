package com.example.banking;

import com.example.banking.util.ConnectionFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Test helper: connects to banking_test (never the real banking database) and empties it. */
public final class TestDatabase {

    private TestDatabase() { }

    public static ConnectionFactory connections() {
        return new ConnectionFactory(
                "jdbc:mysql://localhost:3306/banking_test",
                System.getenv("DB_USERNAME"),
                System.getenv("DB_PASSWORD"));
    }

    /** Deletes all rows, children before parents so foreign keys don't block it. */
    public static void clean(ConnectionFactory cf) throws SQLException {
        List<String> tables = List.of("transaction_audit", "login_audit", "transactions",
                "loan_details", "deposit_details", "accounts", "customers");
        try (Connection c = cf.getConnection(); Statement s = c.createStatement()) {
            for (String table : tables) {
                s.executeUpdate("DELETE FROM " + table);
            }
        }
    }
}