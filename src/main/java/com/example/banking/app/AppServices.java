package com.example.banking.app;

import com.example.banking.ai.IntentParser;
import com.example.banking.service.AccountService;
import com.example.banking.service.AuthService;
import com.example.banking.service.DepositLoanService;
import com.example.banking.service.SessionService;
import com.example.banking.service.TransactionService;

/**
 * All services in one bundle, so menus don't need a new constructor each time a service is added.
 * assistant is null when the AI assistant is turned off in app.properties.
 */
public record AppServices(AuthService auth, AccountService accounts, TransactionService transactions,
                          SessionService sessions, DepositLoanService depositsLoans, IntentParser assistant) {
}