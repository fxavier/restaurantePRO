package com.xavier.backend.identity.service;

import com.xavier.backend.identity.repository.UserAccountRepository;
import java.util.UUID;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

/**
 * Ends open sessions through Spring Session's principal-name index, which login sets to {@code Actor.userId()}:
 * the account id for BackOffice sessions, the employee id for POS sessions. Authorities are resolved once at
 * login, so every change that can take authorities away ends the sessions that hold them.
 */
@Component
class SessionTermination {

    private final FindByIndexNameSessionRepository<?> sessions;
    private final UserAccountRepository accounts;

    SessionTermination(FindByIndexNameSessionRepository<?> sessions, UserAccountRepository accounts) {
        this.sessions = sessions;
        this.accounts = accounts;
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

    /** The employee's POS sessions and the sessions of the account linked to the employee (store authorities). */
    void endEmployeeSessions(UUID employeeId) {
        endAll(employeeId);
        accounts.findByEmployeeId(employeeId).ifPresent(account -> endAll(account.getId()));
    }
}
