package com.xavier.backend.shared.tenancy;

import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The authenticated principal of this thread, for the tenant and store scope resolution. A request's authentication
 * is the resolved actor ({@code config.security}), set before any transaction of the request begins; there is no
 * stored session to load (D176).
 */
public final class CurrentAuthentication {

    private CurrentAuthentication() {
    }

    /** The authenticated (not anonymous) authentication, if any. */
    public static Optional<Authentication> read() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return Optional.of(authentication);
    }
}
