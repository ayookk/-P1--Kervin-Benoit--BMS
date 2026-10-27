package com.example.banking.exception;

public class AuthorizationException extends BankingException {
    public AuthorizationException() { super("Account not found."); }
}