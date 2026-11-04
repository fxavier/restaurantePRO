package com.xavier.backend.identity;

import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The single server-side authorization check (D27, spec §10). Every service use case calls
 * {@code require(...)} before doing anything; controllers never decide authorization.
 *
 * <p>Reads the Spring Security context of the current request: the principal is the {@link Actor} and
 * the granted authorities follow the {@link Permission} format. An installation-wide grant satisfies
 * every store. Failures: no authenticated actor → {@link AuthenticationCredentialsNotFoundException}
 * (401); missing permission → {@link AccessDeniedException} (403). Both become ProblemDetails.
 */
@Component
public class AccessPolicy {

    public Actor current() {
        Object principal = authentication().getPrincipal();
        if (principal instanceof Actor actor) {
            return actor;
        }
        throw new IllegalStateException("Authenticated principal is not an Actor: " + principal.getClass().getName());
    }

    /** Requires an installation-wide grant of {@code permission}. */
    public void require(Permission permission) {
        if (!granted(permission.authority())) {
            throw new AccessDeniedException("Missing permission " + permission);
        }
    }

    /** Requires {@code permission} in {@code storeId}, either store-scoped or installation-wide. */
    public void require(Permission permission, UUID storeId) {
        Objects.requireNonNull(storeId, "storeId");
        if (!granted(permission.authority()) && !granted(permission.authority(storeId))) {
            throw new AccessDeniedException("Missing permission " + permission + " in store " + storeId);
        }
    }

    private static boolean granted(String authority) {
        return authentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
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
