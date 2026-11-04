package com.xavier.backend.access;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

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
 *
 * <p>POS use cases call {@link #requirePos} instead (D39): it also proves the caller is an employee on an enrolled
 * terminal of an active store, and returns that context, so POS commands never take store or terminal from the
 * request. Store scope order (D42): when the store is an input, call {@code require} before any read; for a
 * resource addressed by id, read only its {@code storeId} (absent → 404), then {@code require} (→ 403).
 */
@Component
public class AccessPolicy {

    private static final Logger log = LoggerFactory.getLogger(AccessPolicy.class);
    private static final String ACTIVE_STORE_ATTRIBUTE = AccessPolicy.class.getName() + ".activeStore.";

    private final ObjectProvider<StoreActivity> storeActivity;

    AccessPolicy(ObjectProvider<StoreActivity> storeActivity) {
        this.storeActivity = storeActivity;
    }

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

    /**
     * The gate of every POS use case (D39). Requires an {@link ActorKind#EMPLOYEE} session on an enrolled terminal
     * that holds {@code permission} in the session's store, and that store to be active (D45, through
     * {@link StoreActivity}, asked once per request). A BackOffice session is refused even with an
     * installation-wide grant: POS facts need an employee and a terminal.
     *
     * @return the session's employee, terminal and store; use these, never ids from the request
     * @throws AccessDeniedException (403) for any refusal
     */
    public PosContext requirePos(Permission permission) {
        return requirePosAny(Objects.requireNonNull(permission, "permission"));
    }

    /**
     * {@link #requirePos} for a use case that any one of {@code permissions} allows (e.g. the change stream: dining
     * or production staff). One denial log line, naming all of them.
     */
    public PosContext requirePosAny(Permission... permissions) {
        if (permissions.length == 0) {
            throw new IllegalArgumentException("at least one permission");
        }
        Actor actor = current();
        Permission permission = permissions[0];
        if (actor.kind() != ActorKind.EMPLOYEE || actor.terminalId() == null || actor.storeId() == null) {
            log.warn("Access denied: POS session required: kind={} user={} terminal={} store={} permission={}",
                    actor.kind(), actor.userId(), actor.terminalId(), actor.storeId(),
                    permissions.length == 1 ? permission : List.of(permissions));
            throw new AccessDeniedException("A POS session on an enrolled terminal is required");
        }
        UUID storeId = actor.storeId();
        boolean granted = Arrays.stream(permissions).anyMatch(p ->
                actor.authorities().contains(p.authority()) || actor.authorities().contains(p.authority(storeId)));
        if (!granted) {
            throw denied(actor, permissions.length == 1 ? permission : List.of(permissions), storeId);
        }
        if (!storeIsActive(storeId)) {
            log.warn("Access denied: store inactive: employee={} terminal={} store={} permission={}",
                    actor.employeeId(), actor.terminalId(), storeId, permission);
            throw new AccessDeniedException("Store " + storeId + " is inactive");
        }
        return new PosContext(actor.employeeId(), actor.terminalId(), storeId);
    }

    private boolean storeIsActive(UUID storeId) {
        StoreActivity stores = storeActivity.getIfAvailable();
        if (stores == null) {
            throw new IllegalStateException("No StoreActivity bean: organization implements it (include "
                    + "\"organization\" in a module test, or provide a test StoreActivity)");
        }
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return stores.isActive(storeId);
        }
        String attribute = ACTIVE_STORE_ATTRIBUTE + storeId;
        if (request.getAttribute(attribute, RequestAttributes.SCOPE_REQUEST) instanceof Boolean cached) {
            return cached;
        }
        boolean active = stores.isActive(storeId);
        request.setAttribute(attribute, active, RequestAttributes.SCOPE_REQUEST);
        return active;
    }

    private static AccessDeniedException denied(Actor actor, Object permission, UUID storeId) {
        log.warn("Access denied: kind={} user={} employee={} terminal={} permission={} store={}",
                actor.kind(), actor.userId(), actor.employeeId(), actor.terminalId(), permission,
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
