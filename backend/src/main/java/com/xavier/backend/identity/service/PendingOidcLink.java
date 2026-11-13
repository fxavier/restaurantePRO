package com.xavier.backend.identity.service;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A request, kept in the HTTP session, to link the next IdP identity to the session's account (D144, amended
 * 2026-11-13 by the S57 review: a link request is bound to the login that started it). The OIDC code marks and takes it;
 * {@code LoginSessions} clears it whenever a session starts or restarts (a login, a second-factor verification), so a
 * later login in the same browser, even as the same account, never completes a link it did not ask for.
 */
public final class PendingOidcLink {

    /** The account ({@code UUID}) whose session asked to link. */
    public static final String ACCOUNT = "identity.oidc.pendingLink";
    /** When it asked ({@code Instant}). */
    public static final String REQUESTED_AT = "identity.oidc.pendingLinkAt";

    /** What a session asked for. */
    public record Request(UUID accountId, Instant requestedAt) {
    }

    private PendingOidcLink() {
    }

    public static void mark(HttpSession session, UUID accountId, Instant now) {
        session.setAttribute(ACCOUNT, accountId);
        session.setAttribute(REQUESTED_AT, now);
    }

    /** The request, removed from the session: it is used once, whatever comes of it. */
    public static Optional<Request> take(HttpSession session) {
        if (session == null || !(session.getAttribute(ACCOUNT) instanceof UUID accountId)) {
            clear(session);
            return Optional.empty();
        }
        Instant at = session.getAttribute(REQUESTED_AT) instanceof Instant requested ? requested : Instant.MIN;
        clear(session);
        return Optional.of(new Request(accountId, at));
    }

    public static void clear(HttpSession session) {
        if (session != null) {
            session.removeAttribute(ACCOUNT);
            session.removeAttribute(REQUESTED_AT);
        }
    }
}
