package com.xavier.backend.config.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import jakarta.servlet.http.HttpServletRequest;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * JWT validation, complete and fail closed (D176, security rule 8), one decoder per route family and realm:
 * signature against the realm's key set with the algorithm allow-list {@code RS256}/{@code ES256} (no {@code none},
 * no {@code HS*}); {@code iss} exactly the realm's issuer; {@code aud} contains the API audience; {@code exp} required,
 * {@code nbf} and {@code iat} (required) not in the future, all with the configured clock skew; the claim {@code typ = Bearer}
 * (Keycloak: an ID or a refresh token is never an access token); {@code azp} allowed for the route family. The token
 * picks its decoder by its unverified {@code iss}, among the family's trusted realms only; then everything is verified.
 * A token whose unverified {@code typ} is not {@code Bearer} is refused before that, without fetching any key.
 *
 * <p>Claims grant nothing: the authentication carries no authority. A key set that cannot be fetched (and no key
 * cached) surfaces as {@link AuthenticationServiceException}, which the chain answers 503 {@code IDP_UNAVAILABLE}.
 */
final class BearerAuthentication {

    static final String ACCESS_TOKEN_TYPE = "Bearer";

    private final TrustedRealms realms;
    private final Map<RouteFamily, Map<Realm, AuthenticationManager>> managers = new EnumMap<>(RouteFamily.class);

    BearerAuthentication(TrustedRealms realms, SecurityProperties properties, Clock clock) {
        this.realms = realms;
        for (RouteFamily family : RouteFamily.values()) {
            Map<Realm, AuthenticationManager> byRealm = new EnumMap<>(Realm.class);
            for (Realm realm : family.realms()) {
                realms.issuer(realm).ifPresent(issuer -> byRealm.put(realm, manager(issuer, family, properties,
                        clock)));
            }
            managers.put(family, byRealm);
        }
    }

    /** Whether any realm of {@code family} is trusted; otherwise every call of the family is 503. */
    boolean available(RouteFamily family) {
        return !managers.get(family).isEmpty();
    }

    /** The authentication of a bearer token on a route of {@code family}. */
    AuthenticationManager forFamily(RouteFamily family) {
        return authentication -> {
            if (!available(family)) {
                throw new AuthenticationServiceException("No trusted issuer for the " + family + " routes");
            }
            String token = ((BearerTokenAuthenticationToken) authentication).getToken();
            Optional<JWTClaimsSet> claims = unverifiedClaims(token);
            // refused before any key fetch: an ID or refresh token never needs the IdP to be refused (FE-F34)
            if (claims.isPresent() && !ACCESS_TOKEN_TYPE.equals(claims.get().getClaim("typ"))) {
                throw new InvalidBearerTokenException("Not an access token");
            }
            Optional<AuthenticationManager> manager = claims.map(JWTClaimsSet::getIssuer).flatMap(realms::realmOf)
                    .map(realm -> managers.get(family).get(realm));
            return manager.orElseThrow(() -> new InvalidBearerTokenException("Untrusted issuer"))
                    .authenticate(authentication);
        };
    }

    /** By request path: the API chain serves several families. */
    AuthenticationManagerResolver<HttpServletRequest> byPath() {
        return request -> forFamily(RouteFamily.of(request).orElse(RouteFamily.BACKOFFICE));
    }

    private static Optional<JWTClaimsSet> unverifiedClaims(String token) {
        try {
            return Optional.ofNullable(JWTParser.parse(token).getJWTClaimsSet());
        } catch (ParseException e) {
            return Optional.empty();
        }
    }

    private static AuthenticationManager manager(TrustedRealms.Issuer issuer, RouteFamily family,
                                                 SecurityProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(issuer.keys())
                .jwsAlgorithms(algorithms -> {
                    algorithms.add(SignatureAlgorithm.RS256);
                    algorithms.add(SignatureAlgorithm.ES256);
                })
                .build();
        decoder.setJwtValidator(validator(issuer.uri(), family, properties, clock));
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(jwt -> new JwtAuthenticationToken(jwt, List.of()));
        return new ProviderManager(provider);
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer, RouteFamily family, SecurityProperties properties,
                                               Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(properties.clockSkew());
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);
        // iat required and not in the future (skew allowed); its age is bounded by exp, not here. Spring's
        // JwtIssuedAtValidator is DPoP's: it also refuses an iat older than the skew, i.e. every access token after
        // 30 s (found by KeycloakLoginIT)
        OAuth2TokenValidator<Jwt> issuedAt = new JwtClaimValidator<Instant>(JwtClaimNames.IAT,
                iat -> iat != null && !iat.isAfter(clock.instant().plus(properties.clockSkew())));
        JwtTypeValidator header = JwtTypeValidator.jwt();
        header.setAllowEmpty(true);
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>(List.of(
                header,
                timestamps,
                issuedAt,
                new JwtIssuerValidator(issuer),
                new JwtAudienceValidator(properties.audience()),
                new JwtClaimValidator<String>("typ", ACCESS_TOKEN_TYPE::equals),
                new JwtClaimValidator<String>("azp", family::allowsAuthorizedParty),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank())));
        return new DelegatingOAuth2TokenValidator<>(validators);
    }
}
