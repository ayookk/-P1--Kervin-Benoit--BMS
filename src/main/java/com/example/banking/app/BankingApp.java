package com.example.banking.app;

import com.example.banking.util.AppConfig;
import com.example.banking.util.ConnectionFactory;
import java.sql.Connection;
import java.sql.SQLException;

public class BankingApp {
    public static void main(String[] args) {
        try {
            AppConfig config = AppConfig.load();
            ConnectionFactory connections = new ConnectionFactory(config);
            try (Connection conn = connections.getConnection()) {
                System.out.println("Connected to database: " + conn.getCatalog());
            }
        } catch (IllegalStateException e) {
            System.err.println("Configuration problem: " + e.getMessage());
        } catch (SQLException e) {
            System.err.println("Connection failed: " + e.getMessage());
        }
    }
}