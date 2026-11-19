package com.xavier.bff;

import com.nimbusds.jose.jwk.JWK;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;

/**
 * Logout without a token in any URL: the BFF ends Keycloak's session over the back channel (refresh token plus
 * private_key_jwt), then sends the browser to the end-session endpoint with only client_id and
 * post_logout_redirect_uri, which clears Keycloak's own cookies. The BFF session ends even if Keycloak fails.
 * A caller asking for JSON (the SPA's fetch, which cannot follow a cross-origin redirect) gets that URL as
 * {@code {"logoutUrl": ...}} and navigates there itself (FE-F10).
 */
final class KeycloakLogout implements LogoutHandler, LogoutSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(KeycloakLogout.class);

    private final ClientRegistrationRepository registrations;
    private final OAuth2AuthorizedClientRepository authorizedClients;
    private final RestClient identityProvider;
    private final NimbusJwtClientAuthenticationParametersConverter<OAuth2RefreshTokenGrantRequest> clientAssertion;
    private final JsonMapper json;

    KeycloakLogout(ClientRegistrationRepository registrations, OAuth2AuthorizedClientRepository authorizedClients,
                   RestClient identityProvider, JWK clientKey, JsonMapper json) {
        this.json = json;
        this.registrations = registrations;
        this.authorizedClients = authorizedClients;
        this.identityProvider = identityProvider;
        this.clientAssertion = new NimbusJwtClientAuthenticationParametersConverter<>(registration -> clientKey);
    }

    /** Runs before the BFF session is invalidated, while the refresh token is still in it. */
    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication == null) {
            return;
        }
        OAuth2AuthorizedClient client = authorizedClients.loadAuthorizedClient(SecurityConfig.REGISTRATION_ID, authentication, request);
        String endSession = endSessionEndpoint(registration());
        if (client == null || client.getRefreshToken() == null || endSession == null) {
            return;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>(clientAssertion.convert(
                new OAuth2RefreshTokenGrantRequest(client.getClientRegistration(), client.getAccessToken(), client.getRefreshToken())));
        form.set("client_id", client.getClientRegistration().getClientId());
        form.set("refresh_token", client.getRefreshToken().getTokenValue());
        try {
            identityProvider.post().uri(endSession).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            // The class only: a message could quote the form, and the form holds the refresh token (rule 10).
            log.warn("Keycloak back-channel logout failed ({}); the BFF session ends anyway", e.getClass().getSimpleName());
        }
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        String logoutUrl = logoutUrl(request);
        if (!asksForJson(request)) {
            response.sendRedirect(logoutUrl);
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(Map.of("logoutUrl", logoutUrl)));
    }

    /** Keycloak's end-session page with only client_id and post_logout_redirect_uri; the home page without one. */
    private String logoutUrl(HttpServletRequest request) {
        ClientRegistration registration = registration();
        String home = ServletUriComponentsBuilder.fromContextPath(request).path("/").toUriString();
        String endSession = endSessionEndpoint(registration);
        if (endSession == null) {
            return home;
        }
        return UriComponentsBuilder.fromUriString(endSession)
                .queryParam("client_id", registration.getClientId())
                .queryParam("post_logout_redirect_uri", home)
                .encode().toUriString();
    }

    /** Named explicitly: a browser form's Accept matches JSON only through a wildcard, and keeps the redirect. */
    private static boolean asksForJson(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept == null) {
            return false;
        }
        try {
            return MediaType.parseMediaTypes(accept).stream().anyMatch(MediaType.APPLICATION_JSON::equalsTypeAndSubtype);
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    private ClientRegistration registration() {
        return registrations.findByRegistrationId(SecurityConfig.REGISTRATION_ID);
    }

    private static String endSessionEndpoint(ClientRegistration registration) {
        Object endpoint = registration.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint");
        return endpoint == null ? null : endpoint.toString();
    }
}
