package com.xavier.backend.identity.model.dto;

import com.xavier.backend.access.Actor;
import java.util.Set;
import java.util.UUID;

/**
 * Who is logged in (GET /auth/session and the login responses). {@code userId} is the account for a BackOffice
 * session and the employee for a POS session ({@code Actor.userId()} is always present).
 *
 * @param permissions   exactly {@code Actor.authorities()}: {@code PERM_X} or {@code PERM_X@storeId}; empty while
 *                      {@code mustChangePassword}
 * @param sessionHandle the opaque handle of this session, as in {@code GET /backoffice/users/{id}/sessions}
 */
public record SessionView(UUID userId, UUID employeeId, UUID terminalId, UUID storeId, String name,
                          boolean mustChangePassword, Set<String> permissions, String sessionHandle) {

    public SessionView {
        permissions = Set.copyOf(permissions);
    }

    public static SessionView of(Actor actor, String name, boolean mustChangePassword) {
        return new SessionView(actor.userId(), actor.employeeId(), actor.terminalId(), actor.storeId(), name,
                mustChangePassword, actor.authorities(), null);
    }

    public SessionView withSessionHandle(String handle) {
        return new SessionView(userId, employeeId, terminalId, storeId, name, mustChangePassword, permissions, handle);
    }

    public Actor toActor() {
        return new Actor(userId, employeeId, terminalId, storeId, permissions);
    }
}
