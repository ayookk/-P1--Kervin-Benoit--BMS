package com.example.banking.app;

import com.example.banking.util.DatabaseConnection;
import java.sql.Connection;
import java.sql.SQLException;

public class BankingApp {
    public static void main(String[] args) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            System.out.println("Connected to database: " + conn.getCatalog());
        } catch (SQLException e) {
            System.err.println("Connection failed: " + e.getMessage());
        }
    }
} 


