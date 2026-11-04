package com.xavier.backend.identity.service;

import java.util.UUID;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

/**
 * Ends open sessions through Spring Session's principal-name index, which login sets to {@code Actor.userId()}:
 * the account id for BackOffice sessions, the employee id for POS sessions.
 */
@Component
class SessionTermination {

    private final FindByIndexNameSessionRepository<?> sessions;

    SessionTermination(FindByIndexNameSessionRepository<?> sessions) {
        this.sessions = sessions;
    }

    void endAll(UUID principal) {
        endAllExcept(principal, null);
    }

    /** @param keepSessionId the caller's own session, or {@code null} */
    void endAllExcept(UUID principal, String keepSessionId) {
        sessions.findByPrincipalName(principal.toString()).keySet().stream()
                .filter(id -> !id.equals(keepSessionId))
                .forEach(sessions::deleteById);
    }
}
