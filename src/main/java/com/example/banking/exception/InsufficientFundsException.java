package com.example.banking.exception;

public class InsufficientFundsException extends BankingException {
    public InsufficientFundsException() { super("Insufficient funds."); }
}