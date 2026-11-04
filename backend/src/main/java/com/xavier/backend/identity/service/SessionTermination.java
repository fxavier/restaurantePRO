package com.xavier.backend.identity.service;

import com.xavier.backend.identity.repository.EmployeeStoreAssignmentRepository;
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
    private final EmployeeStoreAssignmentRepository assignments;

    SessionTermination(FindByIndexNameSessionRepository<?> sessions, UserAccountRepository accounts,
                       EmployeeStoreAssignmentRepository assignments) {
        this.sessions = sessions;
        this.accounts = accounts;
        this.assignments = assignments;
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

    /**
     * Every session holding {@code roleId}: accounts with it installation-wide, and employees assigned with it in
     * any store (their POS sessions and their linked accounts).
     */
    void endRoleSessions(UUID roleId) {
        accounts.findIdsHoldingRole(roleId).forEach(this::endAll);
        assignments.findEmployeeIdsWithRole(roleId).forEach(this::endEmployeeSessions);
    }

    /** The employee's POS sessions and the sessions of the account linked to the employee (store authorities). */
    void endEmployeeSessions(UUID employeeId) {
        endAll(employeeId);
        accounts.findByEmployeeId(employeeId).ifPresent(account -> endAll(account.getId()));
    }
}
