package com.xavier.backend.config.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Every test context with the real security chains trusts {@link TestJwtFactory}'s keys for every realm (D189), so
 * no test needs Keycloak. A test of the real realms ({@code KeycloakLoginIT}) or of an unreachable or unset issuer
 * ({@code IdpUnavailableTest}) sets {@code restaurantpro.test.jwt=false} and gets the production realms.
 */
@AutoConfiguration
@ConditionalOnBean(TrustedRealms.class)
@ConditionalOnProperty(name = "restaurantpro.test.jwt", havingValue = "true", matchIfMissing = true)
public class TestJwtAutoConfiguration {

    @Bean
    @Primary
    TrustedRealms testRealms() {
        return TrustedRealms.of(TestJwtFactory.TENANT_ISSUER, TestJwtFactory.OPS_ISSUER, TestJwtFactory.DEVICES_ISSUER,
                issuer -> TestJwtFactory.keys());
    }
}
