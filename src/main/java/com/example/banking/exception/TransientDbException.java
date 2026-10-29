package com.example.banking.exception;

/** A deadlock or lock-wait timeout: the whole operation can safely be retried (doc section 12). */
public class TransientDbException extends PersistenceException {
    public TransientDbException(Throwable cause) {
        super("Temporary database conflict. Please try again.", cause);
    }
}