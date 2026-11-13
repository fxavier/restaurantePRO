package com.xavier.backend.identity.oidc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * The ends of Spring Security's OIDC callback. Success hands the validated ID token's {@code iss}, {@code sub} and
 * {@code amr} to {@link OidcLoginService}, nothing else. Failure ({@code state} not this session's, a bad
 * {@code nonce} or signature, an IdP error, the token endpoint down) lands on the login page's error; the log line
 * carries the OAuth2 error code only, never a code, token or description from the IdP.
 */
@Component
@ConditionalOnOidcEnabled
class OidcLoginHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginHandlers.class);

    private final OidcLoginService logins;
    private final ExternalIdentityService identities;

    OidcLoginHandlers(OidcLoginService logins, ExternalIdentityService identities) {
        this.logins = logins;
        this.identities = identities;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OidcIdToken token = ((OidcUser) authentication.getPrincipal()).getIdToken();
        List<String> methods = token.getAuthenticationMethods();
        Set<String> amr = methods == null ? Set.of() : new HashSet<>(methods);
        logins.complete(token.getIssuer().toString(), token.getSubject(), amr, request, response);
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String error = exception instanceof OAuth2AuthenticationException oauth2
                ? oauth2.getError().getErrorCode() : exception.getClass().getSimpleName();
        log.warn("Federated login failed: {}", error);
        identities.takePendingLink(request.getSession(false));
        response.sendRedirect(OidcRoutes.LOGIN_ERROR);
    }
}
