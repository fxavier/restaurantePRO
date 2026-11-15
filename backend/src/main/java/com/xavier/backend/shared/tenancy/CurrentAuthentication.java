package com.xavier.backend.shared.tenancy;

import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Reads the authenticated principal of this thread for the tenant and store scope resolution, at most once at a time.
 * Spring Security loads a request's context lazily, from the session in the database (Spring Session JDBC), and that
 * read begins a transaction whose binding reads the scopes again; the nested read answers empty (the session tables
 * are unscoped), so resolving never recurses. Removed with Spring Session in S72 (D176).
 */
public final class CurrentAuthentication {

    private static final ThreadLocal<Boolean> RESOLVING = new ThreadLocal<>();

    private CurrentAuthentication() {
    }

    /** The authenticated (not anonymous) authentication; empty while a read on this thread is in progress. */
    public static Optional<Authentication> read() {
        if (RESOLVING.get() != null) {
            return Optional.empty();
        }
        RESOLVING.set(Boolean.TRUE);
        Authentication authentication;
        try {
            authentication = SecurityContextHolder.getContext().getAuthentication();
        } finally {
            RESOLVING.remove();
        }
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return Optional.of(authentication);
    }
}
