package com.xavier.backend.identity.oidc;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional OIDC federation (D144, ADR 0034). The properties always bind (the login page asks whether to show the
 * button); everything else exists only when enabled, and then a missing issuer or client id stops startup. The
 * provider is not contacted at startup ({@link LazyClientRegistrationRepository}); the filter-chain part is
 * {@link OidcLoginConfigurer}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OidcProperties.class)
class OidcConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnOidcEnabled
    static class Enabled {

        Enabled(OidcProperties properties) {
            properties.requireComplete();
        }

        @Bean
        LazyClientRegistrationRepository oidcClientRegistrations(OidcProperties properties, Clock clock) {
            return new LazyClientRegistrationRepository(properties, clock);
        }

        @Bean
        OidcLoginConfigurer oidcLoginConfigurer(LazyClientRegistrationRepository registrations,
                                                OidcLoginHandlers handlers) {
            return new OidcLoginConfigurer(registrations, handlers);
        }
    }
}
