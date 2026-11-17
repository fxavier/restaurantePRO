package com.xavier.bff;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;

/** Token relay of the routes the backoffice-bff token may call (D176: {@code /backoffice/**} and {@code /auth/**}). */
@RestController
class RelayController {


    private final BackendRelay relay;

    RelayController(BackendRelay relay) {
        this.relay = relay;
    }

    /** The authorized-client manager hands over a current access token, refreshing (and rotating) it when it expires. */
    @RequestMapping({"/api/v1/backoffice/**", "/api/v1/auth/**"})
    ResponseEntity<byte[]> relay(@RegisteredOAuth2AuthorizedClient(SecurityConfig.REGISTRATION_ID) OAuth2AuthorizedClient client,
                                 HttpServletRequest request, HttpSession session) throws IOException {
        return relay.relay(request, client.getAccessToken().getTokenValue(), TenantController.selected(session));
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

    @ExceptionHandler(BackendRelay.RequestBodyTooLargeException.class)
    ResponseEntity<ProblemDetail> tooLarge() {
        return BffProblem.PAYLOAD_TOO_LARGE.toResponse();
    }
}
