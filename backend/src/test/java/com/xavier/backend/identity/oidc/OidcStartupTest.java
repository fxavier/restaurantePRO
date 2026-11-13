package com.xavier.backend.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/** D144 startup: disabled needs nothing and creates nothing; enabled but incomplete stops with a clear message. */
class OidcStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OidcConfiguration.class);

    @Test
    void disabledStartsWithoutIssuerClientOrSecretAndCreatesNoOidcBean() {
        runner.withPropertyValues("restaurantpro.security.oidc.enabled=false",
                        "restaurantpro.security.oidc.client-secret-file=/no/such/file")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
                    assertThat(context).doesNotHaveBean(OidcLoginConfigurer.class);
                });
    }

    @Test
    void anEmptyEnabledValueIsDisabled() {
        runner.withPropertyValues("restaurantpro.security.oidc.enabled=")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(ClientRegistrationRepository.class));
    }

    @Test
    void enabledWithoutIssuerAndClientIdStopsStartupNamingThem() {
        runner.withPropertyValues("restaurantpro.security.oidc.enabled=true",
                        "restaurantpro.security.oidc.issuer-uri=", "restaurantpro.security.oidc.client-id=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("restaurantpro.security.oidc.issuer-uri is required")
                            .hasMessageContaining("restaurantpro.security.oidc.client-id is required");
                });
    }
}
