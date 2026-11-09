package com.example.banking.exception;

/** The AI model could not be reached or gave an unusable reply. The rest of the app keeps working. */
public class AssistantUnavailableException extends BankingException {
    public AssistantUnavailableException(Throwable cause) {
        super("The AI assistant is not available right now. Please use the regular menu options.", cause);
    }
}