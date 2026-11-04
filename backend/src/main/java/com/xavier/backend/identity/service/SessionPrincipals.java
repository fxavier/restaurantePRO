package com.xavier.backend.identity.service;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import java.util.UUID;

/**
 * The Spring Session principal-name index of a session: {@code "<kind>:<principal id>"} (D38). An account id and an
 * employee id are different principals even when the UUIDs are equal, so listing or ending one never touches the
 * other's sessions.
 */
public final class SessionPrincipals {

    private SessionPrincipals() {
    }

    public static String of(Actor actor) {
        return of(actor.kind(), actor.userId());
    }

    static String account(UUID accountId) {
        return of(ActorKind.ACCOUNT, accountId);
    }

    static String employee(UUID employeeId) {
        return of(ActorKind.EMPLOYEE, employeeId);
    }

    private static String of(ActorKind kind, UUID principalId) {
        return kind + ":" + principalId;
    }
}
