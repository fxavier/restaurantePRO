package com.xavier.bff;

import com.nimbusds.jose.jwk.JWK;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.io.IOException;

/**
 * Logout without a token in any URL: the BFF ends Keycloak's session over the back channel (refresh token plus
 * private_key_jwt), then sends the browser to the end-session endpoint with only client_id and
 * post_logout_redirect_uri, which clears Keycloak's own cookies. The BFF session ends even if Keycloak fails.
 */
final class KeycloakLogout implements LogoutHandler, LogoutSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(KeycloakLogout.class);

    private final ClientRegistrationRepository registrations;
    private final OAuth2AuthorizedClientRepository authorizedClients;
    private final RestClient identityProvider;
    private final NimbusJwtClientAuthenticationParametersConverter<OAuth2RefreshTokenGrantRequest> clientAssertion;

    KeycloakLogout(ClientRegistrationRepository registrations, OAuth2AuthorizedClientRepository authorizedClients,
                   RestClient identityProvider, JWK clientKey) {
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
        ClientRegistration registration = registration();
        String home = ServletUriComponentsBuilder.fromContextPath(request).path("/").toUriString();
        String endSession = endSessionEndpoint(registration);
        if (endSession == null) {
            response.sendRedirect(home);
            return;
        }
        response.sendRedirect(UriComponentsBuilder.fromUriString(endSession)
                .queryParam("client_id", registration.getClientId())
                .queryParam("post_logout_redirect_uri", home)
                .encode().toUriString());
    }

    private ClientRegistration registration() {
        return registrations.findByRegistrationId(SecurityConfig.REGISTRATION_ID);
    }

    private static String endSessionEndpoint(ClientRegistration registration) {
        Object endpoint = registration.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint");
        return endpoint == null ? null : endpoint.toString();
    }
}
