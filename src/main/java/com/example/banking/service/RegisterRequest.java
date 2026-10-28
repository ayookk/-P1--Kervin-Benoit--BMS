package com.example.banking.service;

/**
 * The registration form, passed from the menu to AuthService.
 * A record: a compact, immutable data carrier (constructor, getters, equals generated for you).
 * The password is a char[] so it can be wiped after hashing.
 */
public record RegisterRequest(String fullName, String email, String phone,
                              String username, char[] password) {
}