package com.xavier.backend.identity.model.dto;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import java.util.Set;
import java.util.UUID;

/**
 * Who is calling ({@code GET /auth/session}, {@code GET /auth/pos-session} and the POS login responses). {@code kind}
 * says which principal it is (D38): {@code ACCOUNT} for a BackOffice caller, where {@code userId} is the account, and
 * {@code EMPLOYEE} for a POS session, where {@code userId} is the employee.
 *
 * @param mustChangePin an employee still has the PIN a manager set (reset-pin); only {@code POST /auth/pos-pin} helps
 * @param permissions   exactly {@code Actor.authorities()}: {@code PERM_X} or {@code PERM_X@storeId}; empty while
 *                      {@code mustChangePin}
 * @param posSession    the POS session token ({@code X-Pos-Session}), in the PIN login response only: the client
 *                      sends it on every POS call; the server keeps only its hash (D182)
 */
public record SessionView(ActorKind kind, UUID userId, UUID employeeId, UUID terminalId, UUID storeId, String name,
                          boolean mustChangePin, Set<String> permissions, String posSession) {

    public SessionView {
        permissions = Set.copyOf(permissions);
    }

    /** A BackOffice caller; {@code name} is the username. */
    public static SessionView account(Actor actor, String name) {
        return new SessionView(actor.kind(), actor.userId(), actor.employeeId(), actor.terminalId(), actor.storeId(),
                name, false, actor.authorities(), null);
    }

    /** A POS session; {@code name} is the employee's. */
    public static SessionView employee(Actor actor, String name, boolean mustChangePin) {
        return new SessionView(actor.kind(), actor.userId(), actor.employeeId(), actor.terminalId(), actor.storeId(),
                name, mustChangePin, actor.authorities(), null);
    }

    /** With the token of the session just opened. */
    public SessionView withPosSession(String token) {
        return new SessionView(kind, userId, employeeId, terminalId, storeId, name, mustChangePin, permissions, token);
    }

    @Override
    public String toString() {
        return "SessionView[kind=" + kind + ", userId=" + userId + ", terminalId=" + terminalId + ", storeId="
                + storeId + "]";
    }
}
