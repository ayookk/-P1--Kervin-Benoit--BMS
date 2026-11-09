package com.example.banking.ai;

import com.example.banking.model.AccountType;
import com.example.banking.model.Money;

/**
 * What the customer asked for: suggested by the AI model, then checked by our own code (IntentParser).
 * A sealed interface: these records are the ONLY possible intents, so a switch over them is exhaustive.
 * An account type of null means "the customer didn't say", and the menu asks them to choose.
 */
public sealed interface AssistantIntent {

    record Transfer(AccountType from, String toAccountNumber, Money amount) implements AssistantIntent { }

    record Deposit(AccountType into, Money amount) implements AssistantIntent { }

    record Withdraw(AccountType from, Money amount) implements AssistantIntent { }

    record CheckBalance(AccountType account) implements AssistantIntent { }

    record ShowHistory(AccountType account) implements AssistantIntent { }

    record ChangePassword() implements AssistantIntent { }

    record NotUnderstood(String reason) implements AssistantIntent { }
}