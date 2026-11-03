package com.example.banking.service;

import static org.junit.jupiter.api.Assertions.*;

import com.example.banking.exception.SessionExpiredException;
import com.example.banking.fakes.InMemoryLoginAuditDAO;
import com.example.banking.fakes.MutableClock;
import com.example.banking.model.LoginOutcome;
import com.example.banking.security.SessionContext;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionServiceTest {

    private MutableClock clock;
    private InMemoryLoginAuditDAO audit;
    private SessionService sessions;
    private SessionContext session;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-03T12:00:00Z"));
        audit = new InMemoryLoginAuditDAO();
        sessions = new SessionService(clock, Duration.ofMinutes(5), audit);
        session = new SessionContext(1, "alice", clock.instant());
    }

    @Test
    void activityKeepsTheSessionAlive() {
        clock.advance(Duration.ofMinutes(4));
        sessions.requireActive(session);              // 4 min idle: fine, timer restarts
        clock.advance(Duration.ofMinutes(4));
        sessions.requireActive(session);              // 8 min total, but only 4 since last activity
        assertTrue(session.isValid());
        assertTrue(audit.entries().isEmpty());
    }

    @Test
    void T12_idleBeyondTimeoutExpiresAndIsAudited() {
        clock.advance(Duration.ofMinutes(5));
        assertThrows(SessionExpiredException.class, () -> sessions.requireActive(session));
        assertFalse(session.isValid());
        assertEquals(LoginOutcome.TIMEOUT, audit.last().outcome());
    }

    @Test
    void expiryIsAuditedOnlyOnce() {
        clock.advance(Duration.ofMinutes(6));
        assertThrows(SessionExpiredException.class, () -> sessions.requireActive(session));
        assertThrows(SessionExpiredException.class, () -> sessions.requireActive(session));
        assertEquals(1, audit.entries().size());
    }

    @Test
    void loggedOutSessionIsRejectedWithoutATimeoutAudit() {
        session.invalidate();                         // as logout does
        assertThrows(SessionExpiredException.class, () -> sessions.requireActive(session));
        assertTrue(audit.entries().isEmpty());
    }
}