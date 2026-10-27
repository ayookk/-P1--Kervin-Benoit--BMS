package com.example.banking.exception;

public class PersistenceException extends BankingException {
    public PersistenceException(String message, Throwable cause) { super(message, cause); }
}