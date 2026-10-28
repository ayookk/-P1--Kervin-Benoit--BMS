package com.example.banking.service;

import com.example.banking.dao.CustomerDAO;
import com.example.banking.dao.LoginAuditDAO;
import com.example.banking.exception.AuthenticationException;
import com.example.banking.exception.ValidationException;
import com.example.banking.model.Customer;
import com.example.banking.model.LoginOutcome;
import com.example.banking.security.PasswordHasher;
import com.example.banking.security.SessionContext;
import com.example.banking.validation.CustomerValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Registration, login with lockout, password change and logout (design doc sections 15, 18.1, 18.2). */
public class AuthService {

    private final CustomerDAO customers;
    private final LoginAuditDAO loginAudit;
    private final PasswordHasher hasher;
    private final CustomerValidator validator;
    private final Clock clock;
    private final int maxFailedLogins;
    private final Duration lockDuration;
    private final String dummyHash;   // used when the username doesn't exist, so timing looks the same

    public AuthService(CustomerDAO customers, LoginAuditDAO loginAudit, PasswordHasher hasher,
                       CustomerValidator validator, Clock clock, int maxFailedLogins, Duration lockDuration) {
        this.customers = customers;
        this.loginAudit = loginAudit;
        this.hasher = hasher;
        this.validator = validator;
        this.clock = clock;
        this.maxFailedLogins = maxFailedLogins;
        this.lockDuration = lockDuration;
        this.dummyHash = hasher.hash("timing-equalizer-not-a-real-password".toCharArray());
    }

    /** Registers a customer. Wipes the password array afterwards. */
    public Customer register(RegisterRequest raw) {
        try {
            RegisterRequest r = normalize(raw);
            validator.validateRegistration(r);
            if (customers.existsByUsername(r.username())) {
                throw new ValidationException("That username is already taken.");
            }
            if (customers.existsByEmail(r.email())) {
                throw new ValidationException("That email is already registered.");
            }
            String hash = hasher.hash(r.password());
            return customers.insert(new Customer(r.fullName(), r.email(), r.phone(), r.username(), hash));
        } finally {
            wipe(raw.password());
        }
    }

    /** Returns a session on success. Every failure gives the SAME generic exception. */
    public SessionContext authenticate(String username, char[] password) {
        try {
            if (username == null || username.isBlank() || password == null || password.length == 0) {
                throw new ValidationException("Username and password are required.");
            }
            String name = username.trim();
            Instant now = clock.instant();
            Optional<Customer> found = customers.findByUsername(name);

            if (found.isEmpty()) {
                hasher.verify(password, dummyHash);              // same work as a real check
                loginAudit.record(null, name, LoginOutcome.FAILURE);
                throw new AuthenticationException();
            }

            Customer c = found.get();
            if (!c.isActive() || c.isLockedAt(now)) {
                hasher.verify(password, dummyHash);
                loginAudit.record(c.getCustomerId(), name, LoginOutcome.LOCKED);
                throw new AuthenticationException();
            }

            if (!hasher.verify(password, c.getPasswordHash())) {
                c.registerFailure(now, maxFailedLogins, lockDuration);
                customers.updateLoginState(c.getCustomerId(), c.getFailedLoginCount(), c.getLockedUntil());
                LoginOutcome outcome = c.isLockedAt(now) ? LoginOutcome.LOCKED : LoginOutcome.FAILURE;
                loginAudit.record(c.getCustomerId(), name, outcome);
                throw new AuthenticationException();
            }

            c.registerSuccess();
            customers.updateLoginState(c.getCustomerId(), 0, null);
            loginAudit.record(c.getCustomerId(), name, LoginOutcome.SUCCESS);
            return new SessionContext(c.getCustomerId(), c.getUsername(), now);
        } finally {
            wipe(password);
        }
    }

    /** Requires the current password again (doc F03). */
    public void changePassword(SessionContext session, char[] currentPassword, char[] newPassword) {
        try {
            Customer c = customers.findById(session.getCustomerId())
                    .orElseThrow(AuthenticationException::new);
            if (!hasher.verify(currentPassword, c.getPasswordHash())) {
                throw new ValidationException("Current password is incorrect.");
            }
            List<String> problems = validator.passwordProblems(newPassword, c.getUsername());
            if (!problems.isEmpty()) {
                throw new ValidationException(String.join(System.lineSeparator(), problems));
            }
            customers.updatePasswordHash(c.getCustomerId(), hasher.hash(newPassword));
        } finally {
            wipe(currentPassword);
            wipe(newPassword);
        }
    }

    public void logout(SessionContext session) {
        if (session != null && session.isValid()) {
            loginAudit.record(session.getCustomerId(), session.getUsername(), LoginOutcome.LOGOUT);
            session.invalidate();
        }
    }

    // ---------- helpers ----------

    private static RegisterRequest normalize(RegisterRequest r) {
        String phone = r.phone() == null || r.phone().isBlank() ? null : r.phone().trim();
        return new RegisterRequest(
                r.fullName() == null ? null : r.fullName().trim(),
                r.email() == null ? null : r.email().trim().toLowerCase(Locale.ROOT),
                phone,
                r.username() == null ? null : r.username().trim(),
                r.password());
    }

    private static void wipe(char[] secret) {
        if (secret != null) {
            Arrays.fill(secret, '\0');
        }
    }
}