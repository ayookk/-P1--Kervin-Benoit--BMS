package com.example.banking.service;

import com.example.banking.dao.LoginAuditDAO;
import com.example.banking.exception.SessionExpiredException;
import com.example.banking.model.LoginOutcome;
import com.example.banking.security.SessionContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Inactivity timeout (doc section 20, P1-F09). Called every time input returns:
 * expired -> invalidate + audit TIMEOUT + SessionExpiredException; otherwise refresh the idle timer.
 */
public class SessionService {

    private final Clock clock;
    private final Duration idleTimeout;
    private final LoginAuditDAO loginAudit;

    public SessionService(Clock clock, Duration idleTimeout, LoginAuditDAO loginAudit) {
        this.clock = clock;
        this.idleTimeout = idleTimeout;
        this.loginAudit = loginAudit;
    }

    public void requireActive(SessionContext session) {
        Instant now = clock.instant();
        if (session.isExpiredAt(now, idleTimeout)) {
            if (session.isValid()) {                       // first time we notice: invalidate and audit once
                session.invalidate();
                loginAudit.record(session.getCustomerId(), session.getUsername(), LoginOutcome.TIMEOUT);
            }
            throw new SessionExpiredException();
        }
        session.touch(now);                                 // still active: restart the idle timer
    }

    public Duration idleTimeout() {
        return idleTimeout;
    }
}