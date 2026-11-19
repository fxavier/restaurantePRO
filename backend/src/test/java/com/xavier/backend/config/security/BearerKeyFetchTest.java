package com.xavier.backend.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.JWKSetUnavailableException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

/**
 * FE-F34: which tokens make the backend fetch Keycloak's key set, and when a token is 401 (refused) rather than 503
 * {@code IDP_UNAVAILABLE} (D176). A token that can never be an access token, or whose algorithm is not allowed, is
 * refused without any fetch; an unknown {@code kid} triggers at most the rate-limited refetch and is then refused;
 * only a key set that cannot be fetched for an otherwise acceptable token is 503.
 */
class BearerKeyFetchTest {

    static final String ISSUER = "https://idp.example/realms/restaurantpro";
    static final RSAKey KEY = rsa("k1");

    final AtomicInteger fetches = new AtomicInteger();

    @Test
    void anIdTokenIsRefusedWithoutFetchingTheKeySet() {
        BearerAuthentication bearer = bearer(unreachableKeySet());

        assertThatThrownBy(() -> authenticate(bearer, signed(KEY, claims("ID"))))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThat(fetches).hasValue(0);
    }

    @Test
    void anHs512TokenIsRefusedByTheAlgorithmAllowListWithoutFetchingTheKeySet() throws JOSEException {
        // typ Bearer, so only the RS256/ES256 allow-list can refuse it (Keycloak signs its refresh tokens HS512)
        String refresh = sign(new MACSigner(new byte[64]), new JWSHeader.Builder(JWSAlgorithm.HS512).keyID("hmac")
                .build(), claims("Bearer"));
        BearerAuthentication bearer = bearer(unreachableKeySet());

        assertThatThrownBy(() -> authenticate(bearer, refresh)).isInstanceOf(InvalidBearerTokenException.class);
        assertThat(fetches).hasValue(0);
    }

    @Test
    void unknownKeyIdsAreRefusedAndRefetchTheKeySetAtMostTwicePerInterval() {
        BearerAuthentication bearer = bearer(TrustedRealms.keySet(keySetSource(() -> new JWKSet(KEY.toPublicJWK()))));
        authenticate(bearer, signed(KEY, claims("Bearer")));

        for (int i = 0; i < 4; i++) {
            String randomKid = signed(rsa(UUID.randomUUID().toString()), claims("Bearer"));
            assertThatThrownBy(() -> authenticate(bearer, randomKid)).isInstanceOf(InvalidBearerTokenException.class);
        }
        assertThat(fetches).hasValue(2);
    }

    @Test
    void anAcceptableTokenIs503WhileTheKeySetCannotBeFetchedEvenWhenRateLimited() {
        BearerAuthentication bearer = bearer(TrustedRealms.keySet(keySetSource(() -> {
            throw new JWKSetUnavailableException("Read timed out");
        })));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> authenticate(bearer, signed(KEY, claims("Bearer"))))
                    .isInstanceOf(AuthenticationServiceException.class);
        }
        assertThat(fetches).hasValue(2);
    }

    @Test
    void aKeySetFetchSlowerThanNimbusDefaultButWithinTheConfiguredTimeoutSucceeds() throws Exception {
        HttpServer idp = slowIdp(Duration.ofMillis(1000));
        try {
            JWKSource<SecurityContext> keys = TrustedRealms.remoteKeySet(issuerOf(idp), Duration.ofSeconds(3));

            assertThat(keys.get(new JWKSelector(new JWKMatcher.Builder().keyID("k1").build()), null)).hasSize(1);
        } finally {
            idp.stop(0);
        }
    }

    @Test
    void aKeySetFetchSlowerThanTheConfiguredTimeoutIsAnUnavailableIdp() throws Exception {
        HttpServer idp = slowIdp(Duration.ofMillis(1000));
        try {
            JWKSource<SecurityContext> keys = TrustedRealms.remoteKeySet(issuerOf(idp), Duration.ofMillis(200));

            assertThatThrownBy(() -> keys.get(new JWKSelector(new JWKMatcher.Builder().keyID("k1").build()), null))
                    .isInstanceOf(KeySourceException.class);
        } finally {
            idp.stop(0);
        }
    }

    /** A Keycloak whose {@code certs} endpoint answers {@link #KEY} after {@code delay}. */
    private static HttpServer slowIdp(Duration delay) throws IOException {
        HttpServer idp = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        idp.createContext("/realms/r/protocol/openid-connect/certs", exchange -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        idp.start();
        return idp;
    }

    private static String issuerOf(HttpServer idp) {
        return "http://127.0.0.1:" + idp.getAddress().getPort() + "/realms/r";
    }

    private BearerAuthentication bearer(JWKSource<SecurityContext> keys) {
        TrustedRealms realms = TrustedRealms.of(ISSUER, "", "", issuer -> keys);
        return new BearerAuthentication(realms, new SecurityProperties(null, "restaurantpro-api",
                Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofSeconds(2)), Clock.systemUTC());
    }

    private static Authentication authenticate(BearerAuthentication bearer, String token) {
        return bearer.forFamily(RouteFamily.BACKOFFICE).authenticate(new BearerTokenAuthenticationToken(token));
    }

    /** The key set of an IdP that times out. */
    private JWKSource<SecurityContext> unreachableKeySet() {
        return (selector, context) -> {
            fetches.incrementAndGet();
            throw new JWKSetUnavailableException("Read timed out");
        };
    }

    interface KeySetFetch {
        JWKSet fetch() throws JWKSetUnavailableException;
    }

    private JWKSetSource<SecurityContext> keySetSource(KeySetFetch fetch) {
        return new JWKSetSource<>() {
            @Override
            public JWKSet getJWKSet(com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator evaluator,
                                    long currentTime, SecurityContext context) throws JWKSetUnavailableException {
                fetches.incrementAndGet();
                return fetch.fetch();
            }

            @Override
            public void close() {
            }
        };
    }

    private static JWTClaimsSet claims(String typ) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject("u1").audience(List.of("restaurantpro-api"))
                .claim("azp", "backoffice-bff").claim("typ", typ).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300))).build();
    }

    private static String signed(RSAKey key, JWTClaimsSet claims) {
        try {
            return sign(new RSASSASigner(key), new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                    .build(), claims);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(JWSSigner signer, JWSHeader header, JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(signer);
        return jwt.serialize();
    }

    private static RSAKey rsa(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
