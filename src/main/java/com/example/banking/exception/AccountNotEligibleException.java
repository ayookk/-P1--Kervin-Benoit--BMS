package com.example.banking.exception;

public class AccountNotEligibleException extends BankingException {
    public AccountNotEligibleException(String message) { super(message); }
}