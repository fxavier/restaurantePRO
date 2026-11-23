package com.xavier.backend.identity.model.dto;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Who is calling ({@code GET /auth/session}, {@code GET /auth/pos-session} and the POS login responses). {@code kind}
 * says which principal it is (D38): {@code ACCOUNT} for a BackOffice caller, where {@code userId} is the account, and
 * {@code EMPLOYEE} for a POS session, where {@code userId} is the employee.
 *
 * @param mustChangePin an employee still has the PIN a manager set (reset-pin); only {@code POST /auth/pos-pin} helps
 * @param permissions   exactly {@code Actor.authorities()}: {@code PERM_X} or {@code PERM_X@storeId}; empty while
 *                      {@code mustChangePin}
 * @param tenantId      the tenant the request was resolved in (its membership, D177), never the request's header;
 *                      {@code null} where no tenant applies
 * @param tenantName    that tenant's legal name, as {@code GET /auth/tenants} lists it
 * @param storeName     the name of the session's store ({@code storeId}); {@code null} where no store applies
 * @param storeTimezone that store's IANA timezone (for example {@code Africa/Maputo}): the POS shows times in it
 * @param currency      the company currency (ISO 4217, single currency, spec §8.4), with the store
 * @param terminalCode  the session's terminal's code (for example {@code POS-02}), which the POS status bar names;
 *                      {@code null} where no terminal applies
 * @param terminalName  that terminal's name
 * @param posSession    the POS session token ({@code X-Pos-Session}), in the PIN login response only: the client
 *                      sends it on every POS call; the server keeps only its hash (D182)
 */
@NullMarked
public record SessionView(ActorKind kind, UUID userId, @Nullable UUID employeeId, @Nullable UUID terminalId,
                          @Nullable UUID storeId, @Nullable String name, boolean mustChangePin, Set<String> permissions,
                          @Nullable UUID tenantId, @Nullable String tenantName, @Nullable String storeName,
                          @Nullable String storeTimezone, @Nullable String currency, @Nullable String terminalCode,
                          @Nullable String terminalName, @Nullable String posSession) {

    public SessionView {
        permissions = Set.copyOf(permissions);
    }

    /** A BackOffice caller; {@code name} is the username. */
    public static SessionView account(Actor actor, @Nullable String name) {
        return new SessionView(actor.kind(), actor.userId(), actor.employeeId(), actor.terminalId(), actor.storeId(),
                name, false, actor.authorities(), null, null, null, null, null, null, null, null);
    }

    /** A POS session; {@code name} is the employee's. */
    public static SessionView employee(Actor actor, @Nullable String name, boolean mustChangePin) {
        return new SessionView(actor.kind(), actor.userId(), actor.employeeId(), actor.terminalId(), actor.storeId(),
                name, mustChangePin, actor.authorities(), null, null, null, null, null, null, null, null);
    }

    /** In the tenant the request was resolved in. */
    public SessionView withTenant(UUID tenant, @Nullable String legalName) {
        return new SessionView(kind, userId, employeeId, terminalId, storeId, name, mustChangePin, permissions, tenant,
                legalName, storeName, storeTimezone, currency, terminalCode, terminalName, posSession);
    }

    /** With what the POS shows of the session's store. */
    public SessionView withStore(String store, String timezone, String currencyCode) {
        return new SessionView(kind, userId, employeeId, terminalId, storeId, name, mustChangePin, permissions,
                tenantId, tenantName, store, timezone, currencyCode, terminalCode, terminalName, posSession);
    }

    /** With the code and name of the session's terminal. */
    public SessionView withTerminal(String code, String terminal) {
        return new SessionView(kind, userId, employeeId, terminalId, storeId, name, mustChangePin, permissions,
                tenantId, tenantName, storeName, storeTimezone, currency, code, terminal, posSession);
    }

    /** With the token of the session just opened. */
    public SessionView withPosSession(String token) {
        return new SessionView(kind, userId, employeeId, terminalId, storeId, name, mustChangePin, permissions,
                tenantId, tenantName, storeName, storeTimezone, currency, terminalCode, terminalName, token);
    }

    @Override
    public String toString() {
        return "SessionView[kind=" + kind + ", userId=" + userId + ", terminalId=" + terminalId + ", storeId="
                + storeId + ", tenantId=" + tenantId + "]";
    }
}
