package com.example.banking.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Hands out JDBC connections. An instance (not static) so tests can point one at banking_test. */
public final class ConnectionFactory {

    private final String url;
    private final String username;
    private final String password;

    public ConnectionFactory(AppConfig config) {
        this(config.dbUrl(), config.dbUsername(), config.dbPassword());
    }

    public ConnectionFactory(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }
}