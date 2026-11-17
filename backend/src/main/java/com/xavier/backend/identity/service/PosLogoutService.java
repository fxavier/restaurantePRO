package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import org.springframework.stereotype.Service;

/** Ends the caller's own POS session (D182), audited as {@code EMPLOYEE_LOGGED_OUT}. */
@Service
public class PosLogoutService {

    private final AccessPolicy accessPolicy;
    private final TerminalSessionService sessions;
    private final LoginAudit audit;

    PosLogoutService(AccessPolicy accessPolicy, TerminalSessionService sessions, LoginAudit audit) {
        this.accessPolicy = accessPolicy;
        this.sessions = sessions;
        this.audit = audit;
    }

    public void logout(String token) {
        audit.loggedOut(accessPolicy.current());
        sessions.end(token);
    }
}
