package com.xavier.bff;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

    /** No usable token any more (refresh refused after reuse detection, or Keycloak not answering in time). */
    @ExceptionHandler(ClientAuthorizationException.class)
    ProblemDetail sessionExpired() {
        return BffProblem.SESSION_EXPIRED.toProblemDetail();
    }

    @ExceptionHandler(IOException.class)
    ProblemDetail backendUnreachable() {
        return BffProblem.BACKEND_UNAVAILABLE.toProblemDetail();
    }

    @ExceptionHandler(BackendRelay.RequestBodyTooLargeException.class)
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
    ProblemDetail tooLarge() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "The request body is larger than the BFF relays");
    }
}
