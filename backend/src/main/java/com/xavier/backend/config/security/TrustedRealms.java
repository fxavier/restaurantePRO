package com.xavier.backend.config.security;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.jwk.source.URLBasedJWKSetSource;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The issuers the backend trusts and where their signing keys come from (D176): exactly one issuer per realm, compared
 * as a whole string. A realm whose issuer is unset is not trusted at all, so a call that needs it is 503
 * {@code IDP_UNAVAILABLE} (fail closed). The key sources are lazy: nothing is fetched before the first token.
 */
public final class TrustedRealms {

    /** One trusted issuer and its key set. */
    record Issuer(String uri, JWKSource<SecurityContext> keys) {
    }

    private final Map<Realm, Issuer> issuers;

    private TrustedRealms(Map<Realm, Issuer> issuers) {
        this.issuers = Map.copyOf(issuers);
    }

    /**
     * @param tenantIssuer the {@code restaurantpro} issuer; blank = not trusted
     * @param opsIssuer     the {@code restaurantpro-ops} issuer; blank = not trusted
     * @param devicesIssuer the {@code restaurantpro-devices} issuer (terminals and bridges); blank = not trusted
     * @param keys          the key source of an issuer
     */
    public static TrustedRealms of(String tenantIssuer, String opsIssuer, String devicesIssuer,
                                   Function<String, JWKSource<SecurityContext>> keys) {
        Map<Realm, Issuer> issuers = new EnumMap<>(Realm.class);
        put(issuers, Realm.TENANT, tenantIssuer, keys);
        put(issuers, Realm.OPS, opsIssuer, keys);
        put(issuers, Realm.DEVICES, devicesIssuer, keys);
        return new TrustedRealms(issuers);
    }

    private static void put(Map<Realm, Issuer> issuers, Realm realm, String uri,
                            Function<String, JWKSource<SecurityContext>> keys) {
        if (uri != null && !uri.isBlank()) {
            issuers.put(realm, new Issuer(uri, keys.apply(uri)));
        }
    }

    /**
     * The key set of a Keycloak issuer, at {@code <issuer>/protocol/openid-connect/certs}, each fetch bounded by
     * {@code timeout} (connect and read; {@code restaurantpro.security.jwks-timeout}): a fetch that takes longer is
     * an unavailable IdP (503).
     */
    static JWKSource<SecurityContext> remoteKeySet(String issuer, Duration timeout) {
        int millis = Math.toIntExact(timeout.toMillis());
        try {
            return keySet(new URLBasedJWKSetSource<>(URI.create(issuer + "/protocol/openid-connect/certs").toURL(),
                    new DefaultResourceRetriever(millis, millis, JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT)));
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Not an issuer URL: " + issuer, e);
        }
    }

    /**
     * An issuer's key set from {@code source} (Keycloak's {@code certs} endpoint in production): fetched lazily,
     * cached 5 min, refetched on an unknown {@code kid} at most twice per 30 s (Nimbus defaults), no retry. A refetch
     * refused by that rate limit while the last fetch succeeded finds no key (the token is 401), never 503: random
     * {@code kid}s neither storm Keycloak nor fake an outage (FE-F34). While fetches fail, it stays 503.
     */
    static JWKSource<SecurityContext> keySet(JWKSetSource<SecurityContext> source) {
        return keySet(source, UnaryOperator.identity());
    }

    /** {@link #keySet(JWKSetSource)} with the cache and rate limit set by {@code tuning} (a test shortens them). */
    static JWKSource<SecurityContext> keySet(JWKSetSource<SecurityContext> source,
                                             UnaryOperator<JWKSourceBuilder<SecurityContext>> tuning) {
        LastFetch fetches = new LastFetch(source);
        return new RateLimitedRefetchFindsNoKey(tuning.apply(JWKSourceBuilder.create(fetches)).retrying(false).build(),
                fetches);
    }

    /** Whether the last fetch of the key set succeeded; a cache hit is not a fetch (an outage is not hidden). */
    private static final class LastFetch implements JWKSetSource<SecurityContext> {

        private final JWKSetSource<SecurityContext> source;
        private volatile boolean succeeded;

        LastFetch(JWKSetSource<SecurityContext> source) {
            this.source = source;
        }

        @Override
        public JWKSet getJWKSet(JWKSetCacheRefreshEvaluator evaluator, long currentTime, SecurityContext context)
                throws KeySourceException {
            try {
                JWKSet keys = source.getJWKSet(evaluator, currentTime, context);
                succeeded = true;
                return keys;
            } catch (KeySourceException | RuntimeException e) {
                succeeded = false;
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    private static final class RateLimitedRefetchFindsNoKey implements JWKSource<SecurityContext> {

        private final JWKSource<SecurityContext> keys;
        private final LastFetch fetches;

        RateLimitedRefetchFindsNoKey(JWKSource<SecurityContext> keys, LastFetch fetches) {
            this.keys = keys;
            this.fetches = fetches;
        }

        @Override
        public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
            try {
                return keys.get(selector, context);
            } catch (RateLimitReachedException e) {
                if (fetches.succeeded) {
                    return List.of();
                }
                throw e;
            }
        }
    }

    Optional<Issuer> issuer(Realm realm) {
        return Optional.ofNullable(issuers.get(realm));
    }

    /** The realm whose issuer is exactly {@code iss}. */
    Optional<Realm> realmOf(String iss) {
        return issuers.entrySet().stream().filter(entry -> entry.getValue().uri().equals(iss))
                .map(Map.Entry::getKey).findFirst();
    }
}
