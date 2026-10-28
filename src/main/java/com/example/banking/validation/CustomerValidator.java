package com.example.banking.validation;

import com.example.banking.exception.ValidationException;
import com.example.banking.service.RegisterRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Registration input rules and the password policy (design doc section 18.1). No database access. */
public class CustomerValidator {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,30}$");
    private static final Pattern PHONE = Pattern.compile("^[+0-9 ()-]{7,30}$");
    private static final int MIN_PASSWORD = 10;
    private static final int MAX_PASSWORD = 128;

    /** Throws one ValidationException listing every problem, so the user can fix them all at once. */
    public void validateRegistration(RegisterRequest r) {
        List<String> errors = new ArrayList<>();

        if (r.fullName() == null || r.fullName().length() < 2 || r.fullName().length() > 120) {
            errors.add("Full name must be 2 to 120 characters.");
        }
        if (r.email() == null || r.email().length() > 254 || !EMAIL.matcher(r.email()).matches()) {
            errors.add("Enter a valid email address, e.g. name@example.com.");
        }
        if (r.phone() != null && !PHONE.matcher(r.phone()).matches()) {
            errors.add("Phone may contain only digits, spaces, +, -, ( ) and must be 7 to 30 characters.");
        }
        if (r.username() == null || !USERNAME.matcher(r.username()).matches()) {
            errors.add("Username must be 3 to 30 characters: letters, digits, dot, underscore or hyphen.");
        }
        errors.addAll(passwordProblems(r.password(), r.username()));

        if (!errors.isEmpty()) {
            throw new ValidationException(String.join(System.lineSeparator(), errors));
        }
    }

    /** Password policy: 10+ characters, at least 3 of 4 character kinds, not the same as the username. */
    public List<String> passwordProblems(char[] password, String username) {
        List<String> problems = new ArrayList<>();
        if (password == null || password.length < MIN_PASSWORD || password.length > MAX_PASSWORD) {
            problems.add("Password must be " + MIN_PASSWORD + " to " + MAX_PASSWORD + " characters.");
            return problems;
        }
        boolean lower = false, upper = false, digit = false, symbol = false;
        for (char ch : password) {
            if (Character.isLowerCase(ch)) lower = true;
            else if (Character.isUpperCase(ch)) upper = true;
            else if (Character.isDigit(ch)) digit = true;
            else symbol = true;
        }
        int kinds = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (symbol ? 1 : 0);
        if (kinds < 3) {
            problems.add("Password needs at least 3 of: lowercase, uppercase, digit, symbol.");
        }
        if (username != null && new String(password).equalsIgnoreCase(username)) {
            problems.add("Password must not be the same as your username.");
        }
        return problems;
    }
}