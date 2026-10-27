package com.example.banking.exception;

public class AuthenticationException extends BankingException {
    public AuthenticationException() { super("Invalid username or password."); }
}