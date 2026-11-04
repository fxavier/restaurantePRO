package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.SessionNotFoundException;
import com.xavier.backend.identity.exception.UserNotFoundException;
import com.xavier.backend.identity.model.dto.SessionSummary;
import com.xavier.backend.identity.repository.UserAccountRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists and revokes a BackOffice user's open sessions (spec §5 "sessões", D25). Sessions are found through Spring
 * Session's principal-name index, which login sets to the user id. Needs {@code USER_MANAGE} installation-wide.
 */
@Service
public class UserSessionService {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final UserAccountRepository accounts;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;

    UserSessionService(FindByIndexNameSessionRepository<? extends Session> sessions, UserAccountRepository accounts,
                       AccessPolicy accessPolicy, AuditLog auditLog) {
        this.sessions = sessions;
        this.accounts = accounts;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> list(UUID userId) {
        return sessionsOf(userId).stream()
                .map(session -> new SessionSummary(SessionHandles.of(session.getId()), session.getCreationTime(),
                        session.getLastAccessedTime(), session.getLastAccessedTime().plus(session.getMaxInactiveInterval())))
                .sorted(Comparator.comparing(SessionSummary::createdAt))
                .toList();
    }

    @Transactional
    public void revoke(UUID userId, String handle) {
        Session session = sessionsOf(userId).stream()
                .filter(candidate -> SessionHandles.of(candidate.getId()).equals(handle))
                .findFirst()
                .orElseThrow(SessionNotFoundException::new);
        sessions.deleteById(session.getId());
        auditLog.recordAsCurrentActor("USER_SESSION_REVOKED", "UserAccount", userId.toString(), null, null, null,
                Map.of("sessionHandle", handle));
    }

    private List<? extends Session> sessionsOf(UUID userId) {
        accessPolicy.require(Permission.USER_MANAGE);
        if (!accounts.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
        return List.copyOf(sessions.findByPrincipalName(userId.toString()).values());
    }
}
