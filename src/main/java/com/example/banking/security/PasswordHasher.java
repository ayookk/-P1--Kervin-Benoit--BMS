package com.example.banking.security;

/**
 * Turns a password into a one-way hash and checks a password against a stored hash.
 * An interface so the algorithm can be swapped (e.g. to bcrypt) without touching the services.
 */
public interface PasswordHasher {

    /** Returns a self-describing hash (algorithm, iterations, salt, hash). Never the password. */
    String hash(char[] rawPassword);

    /** True if rawPassword matches the stored hash. */
    boolean verify(char[] rawPassword, String storedHash);
}