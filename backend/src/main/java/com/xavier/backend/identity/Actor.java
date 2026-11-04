package com.xavier.backend.identity;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who is calling (spec §3, §10). It is the principal of the authenticated session, so it is
 * {@link Serializable} (Spring Session JDBC stores the security context).
 *
 * @param userId      the authenticated account; always present
 * @param employeeId  set for POS logins (employee code + PIN), else {@code null}
 * @param terminalId  set when the session runs on an enrolled terminal, else {@code null}
 * @param storeId     the store the session operates in (POS), else {@code null}
 * @param authorities granted authorities, see {@link Permission} for the format
 */
public record Actor(UUID userId, UUID employeeId, UUID terminalId, UUID storeId, Set<String> authorities)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public Actor {
        Objects.requireNonNull(userId, "userId");
        authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
    }
}
