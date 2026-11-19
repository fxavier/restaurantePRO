package com.xavier.bff;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * FE-F22: the BFF session is one per browser, so another user logging in from another tab takes it over, possibly in
 * the same company. A state-changing call naming the user its tab was loaded for ({@code X-Expected-User}, the
 * Keycloak subject the tab read from {@code X-Relayed-User}) must be that session's user, or nothing is done. A tab
 * names nobody only before its first answer from the BFF, when it holds nothing of anyone, so a missing header passes.
 */
@RestControllerAdvice
class ExpectedUser {

    static final String HEADER = "X-Expected-User";

    /** The OIDC subject of the login: the one user id the BFF knows, independent of the user-name attribute. */
    static String subject(Authentication authentication) {
        return subject((OidcUser) authentication.getPrincipal());
    }

    static String subject(OidcUser user) {
        return user.getSubject();
    }

    /** For a call that changes something (a relayed write, the tenant choice). */
    static void check(HttpServletRequest request, String subject) {
        String expected = request.getHeader(HEADER);
        if (expected != null && !expected.equals(subject)) {
            throw new SessionChangedException();
        }
    }

    static final class SessionChangedException extends RuntimeException {
    }

    @ExceptionHandler(SessionChangedException.class)
    ResponseEntity<ProblemDetail> sessionChanged() {
        return BffProblem.SESSION_CHANGED.toResponse();
    }
}
