package com.xavier.backend.identity.oidc;

import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

/**
 * The OIDC part of the API filter chain (D144), as a Spring Security custom DSL that {@code config.SecurityConfig}
 * applies to that chain only ({@code http.with(...)}); {@code config} needs no import of identity. It is not a
 * {@code Customizer} bean on purpose: Spring Security 7 applies those to every filter chain, the actuator's too.
 *
 * <ul>
 *   <li>{@code GET /api/v1/auth/oidc/authorize} redirects to the IdP (no registration id in the URL: one provider),
 *       with {@code state}, {@code nonce} and PKCE ({@code S256}) kept in the session by Spring;</li>
 *   <li>{@code GET /api/v1/auth/oidc/callback} is the redirection endpoint, where Spring checks all of them and the
 *       ID token's signature, issuer, audience and expiry;</li>
 *   <li>the token endpoint is called with {@link OidcHttp}'s timeouts;</li>
 *   <li>the authorized client (access, refresh and ID tokens) is never stored ({@link #NO_TOKENS_KEPT});</li>
 *   <li>Spring's OAuth2 authentication never reaches the session: {@link OidcLoginService} starts the same session a
 *       password login gets, or leaves a linking session as it was;</li>
 *   <li>the login page is the SPA's: no generated page, and an unauthenticated API call still gets the 401
 *       ProblemDetail of the chain's entry point, never a redirect to the IdP.</li>
 * </ul>
 */
public class OidcLoginConfigurer extends AbstractHttpConfigurer<OidcLoginConfigurer, HttpSecurity> {

    /** D144: access, refresh and ID tokens are discarded after the login. */
    static final OAuth2AuthorizedClientRepository NO_TOKENS_KEPT = new OAuth2AuthorizedClientRepository() {

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
                org.springframework.security.core.Authentication principal,
                jakarta.servlet.http.HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient,
                                         org.springframework.security.core.Authentication principal,
                                         jakarta.servlet.http.HttpServletRequest request,
                                         jakarta.servlet.http.HttpServletResponse response) {
            // discarded
        }

        @Override
        public void removeAuthorizedClient(String clientRegistrationId,
                                           org.springframework.security.core.Authentication principal,
                                           jakarta.servlet.http.HttpServletRequest request,
                                           jakarta.servlet.http.HttpServletResponse response) {
            // nothing is kept
        }
    };

    private final LazyClientRegistrationRepository registrations;
    private final OidcLoginHandlers handlers;

    OidcLoginConfigurer(LazyClientRegistrationRepository registrations, OidcLoginHandlers handlers) {
        this.registrations = registrations;
        this.handlers = handlers;
    }

    @Override
    public void init(HttpSecurity http) {
        http.oauth2Login(login -> login
                .clientRegistrationRepository(registrations)
                .authorizedClientRepository(NO_TOKENS_KEPT)
                .authorizationEndpoint(endpoint -> endpoint
                        .authorizationRequestResolver(new FixedRouteAuthorizationRequestResolver(registrations)))
                .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(OidcHttp.tokenClient()))
                .redirectionEndpoint(endpoint -> endpoint.baseUri(OidcRoutes.CALLBACK))
                .loginProcessingUrl(OidcRoutes.CALLBACK)
                .loginPage(OidcRoutes.LOGIN_PAGE)
                .successHandler(handlers)
                .failureHandler(handlers)
                // after configure(), which would set the chain's session-backed repository
                .withObjectPostProcessor(new ObjectPostProcessor<OAuth2LoginAuthenticationFilter>() {
                    @Override
                    public <O extends OAuth2LoginAuthenticationFilter> O postProcess(O filter) {
                        filter.setSecurityContextRepository(new RequestAttributeSecurityContextRepository());
                        return filter;
                    }
                }));
    }
}
