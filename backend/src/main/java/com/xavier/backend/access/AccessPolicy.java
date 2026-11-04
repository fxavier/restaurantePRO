package com.xavier.backend.access;

import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The single server-side authorization check (D27, spec §10). Every service use case calls
 * {@code require(...)} before doing anything; controllers never decide authorization.
 *
 * <p>Reads the Spring Security context of the current request. The principal is the {@link Actor}, and
 * {@link Actor#authorities()} (in the {@link Permission} format) is the only source of truth for
 * these decisions. S2 builds the {@code Authentication}'s granted authorities from the same set, for
 * URL rules such as actuator. Denials are logged at WARN (who, what, where; no secrets). An installation-wide grant satisfies
 * every store. Failures: no authenticated actor → {@link AuthenticationCredentialsNotFoundException}
 * (401); missing permission → {@link AccessDeniedException} (403). Both become ProblemDetails.
 */
@Component
public class AccessPolicy {

    private static final Logger log = LoggerFactory.getLogger(AccessPolicy.class);

    public Actor current() {
        Object principal = authentication().getPrincipal();
        if (principal instanceof Actor actor) {
            return actor;
        }
        throw new IllegalStateException("Authenticated principal is not an Actor: " + principal.getClass().getName());
    }

    /** Requires an installation-wide grant of {@code permission}. */
    public void require(Permission permission) {
        Actor actor = current();
        if (!actor.authorities().contains(permission.authority())) {
            throw denied(actor, permission, null);
        }
    }

    /** Requires {@code permission} in {@code storeId}, either store-scoped or installation-wide. */
    public void require(Permission permission, UUID storeId) {
        Objects.requireNonNull(storeId, "storeId");
        Actor actor = current();
        if (!actor.authorities().contains(permission.authority())
                && !actor.authorities().contains(permission.authority(storeId))) {
            throw denied(actor, permission, storeId);
        }
    }

    private static AccessDeniedException denied(Actor actor, Permission permission, UUID storeId) {
        log.warn("Access denied: user={} employee={} terminal={} permission={} store={}",
                actor.userId(), actor.employeeId(), actor.terminalId(), permission,
                storeId == null ? "(installation-wide)" : storeId);
        return new AccessDeniedException("Missing permission " + permission
                + (storeId == null ? "" : " in store " + storeId));
    }

    private static Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("No authenticated actor");
        }
        return authentication;
    }
}
