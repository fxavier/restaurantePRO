package com.xavier.bff;

import com.nimbusds.jose.jwk.JWK;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
class SecurityConfig {

    static final String REGISTRATION_ID = "keycloak";
    static final String AUTHORIZATION_PATH = "/oauth2/authorization/" + REGISTRATION_ID;

    @Bean
    SecurityFilterChain bff(HttpSecurity http, ClientRegistrationRepository registrations) {
        http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/bff/step-up", "/error").permitAll()
                        .requestMatchers("/api/v1/backoffice/**", "/api/v1/auth/**", "/bff/**").authenticated()
                        .anyRequest().denyAll())
                // D176: CSRF on the BFF's own session. spa() writes the cookie eagerly; the SPA echoes it as X-XSRF-TOKEN.
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfCookie()))
                .oauth2Login(login -> login
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequests(registrations)))
                        .successHandler(afterLogin()))
                .logout(logout -> logout
                        .logoutUrl("/bff/logout")
                        .logoutSuccessHandler(keycloakLogout(registrations)))
                // An XHR gets a status, never a redirect to Keycloak; the SPA navigates to the login itself.
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()));
        return http.build();
    }

    /** __Host-: Secure, Path=/, no Domain, so a sibling subdomain cannot plant a token the double submit would accept. */
    private static CookieCsrfTokenRepository csrfCookie() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName("__Host-XSRF-TOKEN");
        repository.setCookieCustomizer(cookie -> cookie.secure(true).path("/").sameSite("Lax"));
        return repository;
    }

    /**
     * Spring Security 7 adds PKCE S256 for confidential clients too (ClientSettings.requireProofKey defaults to true);
     * this adds the step-up values when one is pending.
     */
    private static OAuth2AuthorizationRequestResolver authorizationRequests(ClientRegistrationRepository registrations) {
        DefaultOAuth2AuthorizationRequestResolver defaults = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return withStepUp(request, defaults.resolve(request));
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
                return withStepUp(request, defaults.resolve(request, registrationId));
            }
        };
    }

    private static OAuth2AuthorizationRequest withStepUp(HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
        if (authorization == null || request.getSession(false) == null) {
            return authorization;
        }
        StepUp stepUp = StepUp.takePending(request.getSession(false));
        if (stepUp == null) {
            return authorization;
        }
        return OAuth2AuthorizationRequest.from(authorization)
                .additionalParameters(parameters -> {
                    parameters.put("acr_values", stepUp.acrValues());
                    parameters.put("max_age", stepUp.maxAge());
                })
                .build();
    }

    private static AuthenticationSuccessHandler afterLogin() {
        SimpleUrlAuthenticationSuccessHandler home = new SimpleUrlAuthenticationSuccessHandler("/");
        return (request, response, authentication) -> {
            String returnPath = StepUp.takeReturnPath(request.getSession());
            if (returnPath == null) {
                home.onAuthenticationSuccess(request, response, authentication);
            } else {
                response.sendRedirect(returnPath);
            }
        };
    }

    /** RP-initiated logout: after the BFF session ends, the browser ends Keycloak's session too. */
    private static OidcClientInitiatedLogoutSuccessHandler keycloakLogout(ClientRegistrationRepository registrations) {
        OidcClientInitiatedLogoutSuccessHandler handler = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        return handler;
    }

    @Bean
    JWK clientKey(BffProperties properties) {
        return ClientKeyFile.load(properties.clientKeyFile());
    }

    /** private_key_jwt (RFC 7523) on the code exchange. */
    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient(JWK clientKey) {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.addParametersConverter(new NimbusJwtClientAuthenticationParametersConverter<>(registration -> clientKey));
        return client;
    }

    /** private_key_jwt on every refresh; Keycloak rotates the refresh token and Spring keeps the new one. */
    @Bean
    OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenTokenResponseClient(JWK clientKey) {
        RestClientRefreshTokenTokenResponseClient client = new RestClientRefreshTokenTokenResponseClient();
        client.addParametersConverter(new NimbusJwtClientAuthenticationParametersConverter<>(registration -> clientKey));
        return client;
    }

    /** A path the firewall refuses (encoded dots or slashes, for example) is a 400, never relayed. */
    @Bean
    RequestRejectedHandler requestRejectedHandler() {
        return new HttpStatusRequestRejectedHandler();
    }
}
