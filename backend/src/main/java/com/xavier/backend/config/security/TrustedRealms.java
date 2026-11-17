package com.xavier.backend.config.security;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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

    Optional<Issuer> issuer(Realm realm) {
        return Optional.ofNullable(issuers.get(realm));
    }

    /** The realm whose issuer is exactly {@code iss}. */
    Optional<Realm> realmOf(String iss) {
        return issuers.entrySet().stream().filter(entry -> entry.getValue().uri().equals(iss))
                .map(Map.Entry::getKey).findFirst();
    }
}
