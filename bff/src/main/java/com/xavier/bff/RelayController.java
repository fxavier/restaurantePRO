package com.xavier.bff;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Token relay of the routes the backoffice-bff token may call (D176: {@code /backoffice/**} and {@code /auth/**}). */
@RestController
class RelayController {

    private static final String EXPECTED_TENANT = "X-Expected-Tenant";
    private static final String EXPECTED_USER = "X-Expected-User";
    /** Allow-list: every other method, a custom or lower-case one included, must name the expected tenant. */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final BackendRelay relay;

    RelayController(BackendRelay relay) {
        this.relay = relay;
    }

    /** The authorized-client manager hands over a current access token, refreshing (and rotating) it when it expires. */
    @RequestMapping({"/api/v1/backoffice/**", "/api/v1/auth/**"})
    ResponseEntity<byte[]> relay(@RegisteredOAuth2AuthorizedClient(SecurityConfig.REGISTRATION_ID) OAuth2AuthorizedClient client,
                                 @AuthenticationPrincipal OidcUser user, HttpServletRequest request,
                                 HttpSession session) throws IOException {
        String subject = user.getSubject();
        checkExpectedUser(request, subject);
        // Read once: the tenant checked is the tenant relayed, even if another tab switches meanwhile.
        Optional<UUID> selected = TenantController.selected(session);
        String expected = request.getHeader(EXPECTED_TENANT);
        if (selected.isPresent()) {
            if (isUnsafe(request) && !selected.get().toString().equalsIgnoreCase(expected)) {
                throw new TenantChangedException();
            }
            return relay.relay(request, client.getAccessToken().getTokenValue(), selected, subject);
        }
        return relay.relay(request, client.getAccessToken().getTokenValue(), expectedTenant(expected), subject);
    }

    /**
     * FE-F22: the BFF session is one per browser, so another user logging in from another tab takes it over, possibly
     * in the same company. A write naming the user its tab was loaded for (the Keycloak subject, which the tab read from
     * X-Relayed-User) must be that session's user. A tab names nobody only before its first session read, when it holds
     * nothing of anyone, so a missing header is relayed.
     */
    private static void checkExpectedUser(HttpServletRequest request, String subject) {
        String expected = request.getHeader(EXPECTED_USER);
        if (isUnsafe(request) && expected != null && !expected.equals(subject)) {
            throw new SessionChangedException();
        }
    }

    /**
     * FE-F16: the selection is one per session, shared by every tab, so a write must name the tenant its tab shows.
     * Without a selection the tab's expected tenant becomes the relayed one: the backend checks it against the caller's
     * memberships (D177), so a tab pinned to a company the session's user does not belong to (another user logged in
     * from another tab) is refused instead of acting in that user's only company.
     */
    private static Optional<UUID> expectedTenant(String expected) {
        if (expected == null) {
            return Optional.empty();
        }
        if (!TenantController.isCanonicalUuid(expected)) {
            throw new TenantController.InvalidTenantSelectionException();
        }
        return Optional.of(UUID.fromString(expected));
    }

    private static boolean isUnsafe(HttpServletRequest request) {
        return !SAFE_METHODS.contains(request.getMethod());
    }

    /**
     * The refresh failed. Only a transport failure (timeout, refused connection: an I/O cause) or a 5xx of Keycloak
     * means the IdP is unavailable: the session's tokens stay and the SPA retries later. Any refusal (400 invalid_grant
     * after reuse detection, 401 or 403 for a disabled client or a rejected assertion) means the session can no longer
     * get a token. Spring reports both kinds with one error code, so the cause decides.
     */
    @ExceptionHandler(ClientAuthorizationException.class)
    ResponseEntity<ProblemDetail> refreshFailed(ClientAuthorizationException failure) {
        return (identityProviderUnavailable(failure) ? BffProblem.IDP_UNAVAILABLE : BffProblem.SESSION_EXPIRED)
                .toResponse();
    }

    private static boolean identityProviderUnavailable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            // a read timeout during the body surfaces as a bare RestClientException over a SocketTimeoutException
            if (cause instanceof ResourceAccessException || cause instanceof HttpServerErrorException
                    || cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<ProblemDetail> backendUnreachable() {
        return BffProblem.BACKEND_UNAVAILABLE.toResponse();
    }

    static final class TenantChangedException extends RuntimeException {
    }

    static final class SessionChangedException extends RuntimeException {
    }

    @ExceptionHandler(SessionChangedException.class)
    ResponseEntity<ProblemDetail> sessionChanged() {
        return BffProblem.SESSION_CHANGED.toResponse();
    }

    @ExceptionHandler(TenantController.InvalidTenantSelectionException.class)
    ProblemDetail invalidExpectedTenant() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "X-Expected-Tenant must be a UUID");
    }

    @ExceptionHandler(TenantChangedException.class)
    ResponseEntity<ProblemDetail> tenantChanged() {
        return BffProblem.TENANT_CHANGED.toResponse();
    }

    @ExceptionHandler(BackendRelay.RequestBodyTooLargeException.class)
    ResponseEntity<ProblemDetail> tooLarge() {
        return BffProblem.PAYLOAD_TOO_LARGE.toResponse();
    }
}
