package com.example.banking.service;

import static org.junit.jupiter.api.Assertions.*;

import com.example.banking.exception.AuthenticationException;
import com.example.banking.exception.ValidationException;
import com.example.banking.fakes.InMemoryCustomerDAO;
import com.example.banking.fakes.InMemoryLoginAuditDAO;
import com.example.banking.fakes.MutableClock;
import com.example.banking.model.Customer;
import com.example.banking.model.LoginOutcome;
import com.example.banking.security.Pbkdf2PasswordHasher;
import com.example.banking.security.SessionContext;
import com.example.banking.validation.CustomerValidator;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthServiceTest {

    private static final String GOOD_PASSWORD = "Str0ng!Pass";

    private InMemoryCustomerDAO customers;
    private InMemoryLoginAuditDAO audit;
    private MutableClock clock;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        customers = new InMemoryCustomerDAO();
        audit = new InMemoryLoginAuditDAO();
        clock = new MutableClock(Instant.parse("2026-10-28T12:00:00Z"));
        auth = new AuthService(customers, audit, new Pbkdf2PasswordHasher(1_000),
                new CustomerValidator(), clock, 5, Duration.ofMinutes(15));
    }

    private Customer registerAlice() {
        return auth.register(new RegisterRequest("Alice Smith", "Alice@Example.com", "555 123 4567",
                "alice", GOOD_PASSWORD.toCharArray()));
    }

    // ---------- registration ----------

    @Test
    void T01_registerStoresHashNotPlaintext() {
        registerAlice();
        Customer stored = customers.findByUsername("alice").orElseThrow();
        assertFalse(stored.getPasswordHash().contains(GOOD_PASSWORD));
        assertTrue(stored.getPasswordHash().startsWith("pbkdf2_sha256$"));
        assertEquals("alice@example.com", stored.getEmail());   // email normalized to lowercase
    }

    @Test
    void T02_duplicateUsernameIsRejected() {
        registerAlice();
        assertThrows(ValidationException.class, () -> auth.register(new RegisterRequest(
                "Other Person", "other@example.com", null, "alice", GOOD_PASSWORD.toCharArray())));
    }

    @Test
    void weakPasswordIsRejectedAndNothingIsSaved() {
        ValidationException e = assertThrows(ValidationException.class, () -> auth.register(
                new RegisterRequest("Bob Jones", "bob@example.com", null, "bob", "short".toCharArray())));
        assertTrue(e.getMessage().contains("Password must be"));
        assertFalse(customers.existsByUsername("bob"));
    }

    @Test
    void allValidationProblemsAreReportedTogether() {
        ValidationException e = assertThrows(ValidationException.class, () -> auth.register(
                new RegisterRequest("", "not-an-email", null, "x", "short".toCharArray())));
        String msg = e.getMessage();
        assertTrue(msg.contains("Full name"));
        assertTrue(msg.contains("email"));
        assertTrue(msg.contains("Username"));
        assertTrue(msg.contains("Password"));
    }

    @Test
    void passwordArrayIsWipedAfterRegistration() {
        char[] password = GOOD_PASSWORD.toCharArray();
        auth.register(new RegisterRequest("Carol King", "carol@example.com", null, "carol", password));
        for (char ch : password) {
            assertEquals('\0', ch);
        }
    }

    // ---------- login ----------

    @Test
    void T03_correctPasswordCreatesSession() {
        Customer alice = registerAlice();
        SessionContext session = auth.authenticate("alice", GOOD_PASSWORD.toCharArray());
        assertEquals(alice.getCustomerId(), session.getCustomerId());
        assertEquals(LoginOutcome.SUCCESS, audit.last().outcome());
    }

    @Test
    void T04_wrongPasswordAndUnknownUserGiveTheSameMessage() {
        registerAlice();
        AuthenticationException wrongPassword = assertThrows(AuthenticationException.class,
                () -> auth.authenticate("alice", "Wrong!Pass99".toCharArray()));
        assertEquals(LoginOutcome.FAILURE, audit.last().outcome());

        AuthenticationException unknownUser = assertThrows(AuthenticationException.class,
                () -> auth.authenticate("nobody", "Wrong!Pass99".toCharArray()));
        assertEquals(LoginOutcome.FAILURE, audit.last().outcome());
        assertNull(audit.last().customerId());

        assertEquals(wrongPassword.getMessage(), unknownUser.getMessage());
    }

    @Test
    void T05_fiveFailuresLockTheAccountUntil15MinutesPass() {
        registerAlice();
        for (int i = 0; i < 5; i++) {
            assertThrows(AuthenticationException.class,
                    () -> auth.authenticate("alice", "Wrong!Pass99".toCharArray()));
        }
        assertEquals(LoginOutcome.LOCKED, audit.last().outcome());

        // even the correct password is refused while locked
        assertThrows(AuthenticationException.class,
                () -> auth.authenticate("alice", GOOD_PASSWORD.toCharArray()));
        assertEquals(LoginOutcome.LOCKED, audit.last().outcome());

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertNotNull(auth.authenticate("alice", GOOD_PASSWORD.toCharArray()));
    }

    @Test
    void successfulLoginResetsTheFailureCounter() {
        registerAlice();
        for (int i = 0; i < 4; i++) {
            assertThrows(AuthenticationException.class,
                    () -> auth.authenticate("alice", "Wrong!Pass99".toCharArray()));
        }
        auth.authenticate("alice", GOOD_PASSWORD.toCharArray());
        assertEquals(0, customers.findByUsername("alice").orElseThrow().getFailedLoginCount());
    }

    @Test
    void blankCredentialsAreRejected() {
        assertThrows(ValidationException.class, () -> auth.authenticate(" ", "x".toCharArray()));
    }

    // ---------- password change and logout ----------

    @Test
    void changePasswordRequiresTheCurrentPassword() {
        registerAlice();
        SessionContext session = auth.authenticate("alice", GOOD_PASSWORD.toCharArray());
        assertThrows(ValidationException.class, () -> auth.changePassword(session,
                "Wrong!Pass99".toCharArray(), "N3w!Password".toCharArray()));

        auth.changePassword(session, GOOD_PASSWORD.toCharArray(), "N3w!Password".toCharArray());
        assertNotNull(auth.authenticate("alice", "N3w!Password".toCharArray()));
    }

    @Test
    void logoutInvalidatesSessionAndIsAudited() {
        registerAlice();
        SessionContext session = auth.authenticate("alice", GOOD_PASSWORD.toCharArray());
        auth.logout(session);
        assertFalse(session.isValid());
        assertEquals(LoginOutcome.LOGOUT, audit.last().outcome());
    }
}