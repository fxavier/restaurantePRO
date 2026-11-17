package com.xavier.bff;

import com.nimbusds.jose.jwk.JWK;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HttpSessionMutexListener;

import java.util.List;

@Configuration
class SecurityConfig {

    static final String REGISTRATION_ID = "keycloak";
    static final String AUTHORIZATION_PATH = "/oauth2/authorization/" + REGISTRATION_ID;

    @Bean
    SecurityFilterChain bff(HttpSecurity http, ClientRegistrationRepository registrations, KeycloakLogout keycloakLogout) {
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
                        .addLogoutHandler(keycloakLogout)
                        .logoutSuccessHandler(keycloakLogout))
                // An XHR gets a status, never a redirect to Keycloak; the SPA navigates to the login itself.
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, failure) -> BffProblem.SESSION_EXPIRED.writeTo(response)))
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

    @Bean
    KeycloakLogout keycloakLogout(ClientRegistrationRepository registrations, OAuth2AuthorizedClientRepository authorizedClients,
                                  RestClient identityProviderRestClient, JWK clientKey) {
        return new KeycloakLogout(registrations, authorizedClients, identityProviderRestClient, clientKey);
    }

    /** The tokens live in the BFF's HTTP session: one set per login, gone with the session (not one per user name). */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
                                                          OAuth2AuthorizedClientRepository authorizedClients,
                                                          OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenTokenResponseClient) {
        DefaultOAuth2AuthorizedClientManager manager = new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken(refresh -> refresh.accessTokenResponseClient(refreshTokenTokenResponseClient))
                .build());
        return new OneRefreshPerSession(manager);
    }

    /**
     * Gives every session a dedicated mutex at creation, which {@link OneRefreshPerSession} locks. Without it
     * WebUtils falls back to the HttpSession object, which is stable on Tomcat only by implementation detail.
     */
    @Bean
    HttpSessionMutexListener sessionMutexListener() {
        return new HttpSessionMutexListener();
    }

    /** The BFF's own calls to Keycloak (code exchange, refresh, back-channel logout) fail fast when it hangs. */
    @Bean
    RestClient identityProviderRestClient(BffProperties properties) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(properties.idpConnectTimeout());
        requests.setReadTimeout(properties.idpReadTimeout());
        return RestClient.builder()
                .requestFactory(requests)
                .messageConverters(List.of(new FormHttpMessageConverter(), new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }

    @Bean
    JWK clientKey(BffProperties properties) {
        return ClientKeyFile.load(properties.clientKeyFile());
    }

    /** private_key_jwt (RFC 7523) on the code exchange. */
    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient(
            JWK clientKey, RestClient identityProviderRestClient) {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(identityProviderRestClient);
        client.addParametersConverter(new NimbusJwtClientAuthenticationParametersConverter<>(registration -> clientKey));
        return client;
    }

    /** private_key_jwt on every refresh; Keycloak rotates the refresh token and Spring keeps the new one. */
    @Bean
    OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenTokenResponseClient(
            JWK clientKey, RestClient identityProviderRestClient) {
        RestClientRefreshTokenTokenResponseClient client = new RestClientRefreshTokenTokenResponseClient();
        client.setRestClient(identityProviderRestClient);
        client.addParametersConverter(new NimbusJwtClientAuthenticationParametersConverter<>(registration -> clientKey));
        return client;
    }

    /** A path the firewall refuses (encoded dots or slashes, for example) is a 400, never relayed. */
    @Bean
    RequestRejectedHandler requestRejectedHandler() {
        return new HttpStatusRequestRejectedHandler();
    }
}
