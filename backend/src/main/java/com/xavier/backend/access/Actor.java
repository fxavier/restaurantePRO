package com.xavier.backend.access;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who is calling (spec §3, §10, D38). It is the principal of the authenticated session, so it is
 * {@link Serializable} (Spring Session JDBC stores the security context). Changing its shape changes
 * {@code serialVersionUID}: sessions stored before an upgrade then read as logged out (401) and users log in again.
 *
 * <p>Business facts store typed columns, never the bare {@code userId}: POS facts store {@code *_employee_id}
 * (from {@code AccessPolicy.requirePos}), BackOffice facts store {@code *_account_id}.
 *
 * @param kind        {@link ActorKind#ACCOUNT} (BackOffice) or {@link ActorKind#EMPLOYEE} (POS); always present
 * @param userId      the principal id: the account id for {@code ACCOUNT}, the employee id for {@code EMPLOYEE};
 *                    always present
 * @param employeeId  the employee for {@code EMPLOYEE} (equal to {@code userId}); {@code null} for {@code ACCOUNT}
 * @param terminalId  the enrolled terminal of a POS session, else {@code null}
 * @param storeId     the store the session operates in (POS), else {@code null}
 * @param authorities granted authorities, see {@link Permission} for the format
 */
public record Actor(ActorKind kind, UUID userId, UUID employeeId, UUID terminalId, UUID storeId,
                    Set<String> authorities) implements Serializable {

    @Serial
    private static final long serialVersionUID = 2L;

    public Actor {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(userId, "userId");
        if (kind == ActorKind.EMPLOYEE && !userId.equals(employeeId)) {
            throw new IllegalArgumentException("An EMPLOYEE actor's principal id is its employee id");
        }
        if (kind == ActorKind.ACCOUNT && employeeId != null) {
            throw new IllegalArgumentException("An ACCOUNT actor carries no employee id");
        }
        authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
    }

    /** A BackOffice session. */
    public static Actor account(UUID accountId, Set<String> authorities) {
        return new Actor(ActorKind.ACCOUNT, accountId, null, null, null, authorities);
    }

    /** A POS session of {@code employeeId} on {@code terminalId}, scoped to {@code storeId}. */
    public static Actor employee(UUID employeeId, UUID terminalId, UUID storeId, Set<String> authorities) {
        return new Actor(ActorKind.EMPLOYEE, employeeId, employeeId, terminalId, storeId, authorities);
    }

    /**
     * The Idempotency-Key scope of {@code operation} for this principal, {@code "<op>:<kind>:<id>"} (D38), so an
     * account and an employee with the same UUID never share a key space.
     */
    public String idempotencyScope(String operation) {
        return Objects.requireNonNull(operation, "operation") + ":" + kind + ":" + userId;
    }
}
