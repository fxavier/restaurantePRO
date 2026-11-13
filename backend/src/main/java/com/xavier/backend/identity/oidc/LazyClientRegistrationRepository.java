package com.xavier.backend.identity.oidc;

import com.xavier.backend.identity.oidc.exception.OidcUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * The installation's one OpenID Provider (D144), resolved from its discovery document on first use and then cached.
 * Spring Boot's issuer-based registration fetches it at startup instead, so an installation without Internet would not
 * start (§11). A failed discovery is not cached: it answers 503 {@code OIDC_UNAVAILABLE} and the next request tries
 * again. PKCE is required whether the client is confidential or public (no secret file).
 */
public class LazyClientRegistrationRepository implements ClientRegistrationRepository {

    /** The only registration; it is never part of a URL (the routes are fixed, PRD R5 S57). */
    public static final String REGISTRATION_ID = "oidc";
    static final String REDIRECT_URI = "{baseUrl}" + OidcRoutes.CALLBACK;

    private static final Logger log = LoggerFactory.getLogger(LazyClientRegistrationRepository.class);

    private final OidcProperties properties;
    private volatile ClientRegistration registration;

    public LazyClientRegistrationRepository(OidcProperties properties) {
        this.properties = properties;
    }

    /**
     * @return the registration, or {@code null} for any other id
     * @throws OidcUnavailableException while the discovery document cannot be fetched
     */
    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (!REGISTRATION_ID.equals(registrationId)) {
            return null;
        }
        ClientRegistration cached = registration;
        return cached != null ? cached : discover();
    }

    private synchronized ClientRegistration discover() {
        if (registration != null) {
            return registration;
        }
        ClientRegistration.Builder discovered;
        try {
            discovered = ClientRegistrations.fromIssuerLocation(properties.issuerUri());
        } catch (RuntimeException e) {
            log.warn("OIDC discovery at {} failed ({}); the OIDC routes answer 503 until it succeeds",
                    properties.issuerUri(), e.getClass().getSimpleName());
            throw new OidcUnavailableException();
        }
        String secret = properties.clientSecret();
        registration = discovered
                .registrationId(REGISTRATION_ID)
                .clientId(properties.clientId())
                .clientSecret(secret)
                .clientAuthenticationMethod(secret == null ? ClientAuthenticationMethod.NONE
                        : ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .redirectUri(REDIRECT_URI)
                .scope(properties.scopes())
                .clientName(properties.displayName())
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .build();
        log.info("OIDC provider {} discovered", properties.issuerUri());
        return registration;
    }
}
