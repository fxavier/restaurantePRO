package com.xavier.backend.identity.oidc;

import com.xavier.backend.identity.oidc.exception.OidcUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.client.RestClient;

/**
 * The installation's one OpenID Provider (D144), resolved from its discovery document on first use and then cached.
 * Spring Boot's issuer-based registration fetches it at startup instead, so an installation without Internet would not
 * start (§11).
 *
 * <p>While the IdP cannot be reached the OIDC routes answer 503 {@code OIDC_UNAVAILABLE}, and never wait long (S57
 * review): the document is fetched with {@link OidcHttp}'s timeouts and without a lock (concurrent requests do not
 * queue behind one slow fetch), and a failure is remembered for {@link #FAILURE_MEMORY}, during which the answer is
 * 503 at once; the first request after that tries again. The document's {@code issuer} must equal
 * {@code issuer-uri} exactly (the ID token's {@code iss} is checked against it). PKCE is required whether the client
 * is confidential or public (no secret file).
 */
public class LazyClientRegistrationRepository implements ClientRegistrationRepository {

    /** The only registration; it is never part of a URL (the routes are fixed, PRD R5 S57). */
    public static final String REGISTRATION_ID = "oidc";
    static final String REDIRECT_URI = "{baseUrl}" + OidcRoutes.CALLBACK;
    static final Duration FAILURE_MEMORY = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(LazyClientRegistrationRepository.class);

    private final OidcProperties properties;
    private final Clock clock;
    private final RestClient http;
    private volatile ClientRegistration registration;
    private volatile Instant unavailableUntil = Instant.MIN;

    public LazyClientRegistrationRepository(OidcProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.http = OidcHttp.discoveryClient();
    }

    /**
     * @return the registration, or {@code null} for any other id
     * @throws OidcUnavailableException while the discovery document cannot be fetched or is not this issuer's
     */
    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (!REGISTRATION_ID.equals(registrationId)) {
            return null;
        }
        ClientRegistration cached = registration;
        if (cached != null) {
            return cached;
        }
        if (clock.instant().isBefore(unavailableUntil)) {
            throw new OidcUnavailableException();
        }
        try {
            ClientRegistration discovered = discover();
            registration = discovered; // two concurrent fetches build the same registration
            log.info("OIDC provider {} discovered", properties.issuerUri());
            return discovered;
        } catch (RuntimeException e) {
            unavailableUntil = clock.instant().plus(FAILURE_MEMORY);
            log.warn("OIDC discovery of {} failed: {} ({}); the OIDC routes answer 503 for {} s",
                    properties.issuerUri(), e.getMessage(), e.getClass().getSimpleName(),
                    FAILURE_MEMORY.toSeconds());
            throw new OidcUnavailableException();
        }
    }

    private ClientRegistration discover() {
        String issuer = properties.issuerUri();
        Map<String, Object> configuration = http.get()
                .uri(issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration")
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
        Object announced = configuration == null ? null : configuration.get("issuer");
        if (!issuer.equals(announced)) {
            throw new IllegalStateException("the discovery document names issuer '" + announced
                    + "', not the configured issuer-uri '" + issuer + "' (they must be equal, trailing slash included)");
        }
        String secret = properties.clientSecret();
        return ClientRegistrations.fromOidcConfiguration(configuration)
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
    }
}
