package com.example.banking.exception;

public class SessionExpiredException extends BankingException {
    public SessionExpiredException() { super("Your session has expired. Please log in again."); }
}