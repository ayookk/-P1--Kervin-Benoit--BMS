package com.example.banking.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class Pbkdf2PasswordHasherTest {

    // Low iteration count only so tests run fast; the app uses 600,000 from app.properties.
    private final PasswordHasher hasher = new Pbkdf2PasswordHasher(1_000);

    @Test
    void storedHashNeverContainsThePlainPassword() {
        String stored = hasher.hash("Secret#Pass123".toCharArray());
        assertFalse(stored.contains("Secret#Pass123"));
        assertTrue(stored.startsWith("pbkdf2_sha256$1000$"));
    }

    @Test
    void correctPasswordVerifies() {
        String stored = hasher.hash("Secret#Pass123".toCharArray());
        assertTrue(hasher.verify("Secret#Pass123".toCharArray(), stored));
    }

    @Test
    void wrongPasswordIsRejected() {
        String stored = hasher.hash("Secret#Pass123".toCharArray());
        assertFalse(hasher.verify("secret#pass123".toCharArray(), stored));
    }

    @Test
    void samePasswordGivesDifferentHashesBecauseOfSalt() {
        String first = hasher.hash("Secret#Pass123".toCharArray());
        String second = hasher.hash("Secret#Pass123".toCharArray());
        assertNotEquals(first, second);
    }

    @Test
    void garbageStoredValueIsRejectedNotCrashed() {
        assertFalse(hasher.verify("anything".toCharArray(), "not-a-real-hash"));
    }
}