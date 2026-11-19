package com.xavier.backend.config.security;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The frozen security properties of R6 (spec "frozen contracts"; D176, D184): one issuer per realm (unset = not
 * trusted, every call needing it is 503), the API audience, the clock skew of the time claims, the step-up max age
 * and the connect and read timeout of a key-set fetch (FE-F34).
 */
@ConfigurationProperties(prefix = "restaurantpro.security")
record SecurityProperties(Issuers issuers, String audience, Duration clockSkew, Duration stepUpMaxAge,
                          Duration jwksTimeout) {

    /** {@code restaurantpro.security.issuers.*}; a blank value means the realm is not trusted. */
    record Issuers(String tenant, String ops, String devices) {
    }

    SecurityProperties {
        issuers = issuers == null ? new Issuers("", "", "") : issuers;
        Objects.requireNonNull(audience, "restaurantpro.security.audience");
        Objects.requireNonNull(clockSkew, "restaurantpro.security.clock-skew");
        Objects.requireNonNull(stepUpMaxAge, "restaurantpro.security.step-up-max-age");
        Objects.requireNonNull(jwksTimeout, "restaurantpro.security.jwks-timeout");
    }
}
