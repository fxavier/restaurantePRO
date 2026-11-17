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

import java.io.IOException;

/** Token relay of the routes the backoffice-bff token may call (D176: {@code /backoffice/**} and {@code /auth/**}). */
@RestController
class RelayController {

    /** Spring Security's error code when the token endpoint could not be reached or answered with an error status. */
    private static final String INVALID_TOKEN_RESPONSE = "invalid_token_response";

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
     * The refresh failed. A transport failure or a 5xx of Keycloak surfaces as Spring's {@code invalid_token_response}
     * and leaves the session's tokens in place: the IdP is unavailable. Anything else (refresh refused after reuse
     * detection, for example) means the session can no longer get a token.
     */
    @ExceptionHandler(ClientAuthorizationException.class)
    ResponseEntity<ProblemDetail> refreshFailed(ClientAuthorizationException failure) {
        BffProblem problem = INVALID_TOKEN_RESPONSE.equals(failure.getError().getErrorCode())
                ? BffProblem.IDP_UNAVAILABLE
                : BffProblem.SESSION_EXPIRED;
        return problem.toResponse();
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
