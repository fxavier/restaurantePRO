package com.xavier.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The R5 PRD freezes environment names (Compose, S62) that Spring's relaxed binding does not map to their properties
 * (e.g. {@code RESTAURANTPRO_MFA_MODE} is not {@code restaurantpro.security.mfa.mode}). {@code application.yaml}
 * binds each one with an explicit placeholder; this test sets every frozen name as an environment variable would and
 * reads the property, and reads the defaults when none is set.
 */
class FrozenConfigurationNamesTest {

    /** Frozen environment name → property it must set, with a non-default value. */
    static final Map<String, String[]> FROZEN = new LinkedHashMap<>();

    static {
        FROZEN.put("RESTAURANTPRO_MFA_MODE", new String[] {"restaurantpro.security.mfa.mode", "SENSITIVE"});
        FROZEN.put("RESTAURANTPRO_MFA_STEP_UP_MAX_AGE",
                new String[] {"restaurantpro.security.mfa.step-up-max-age", "PT5M"});
        FROZEN.put("RESTAURANTPRO_MFA_KEY_FILE", new String[] {"restaurantpro.security.mfa.key-file", "/run/k"});
        FROZEN.put("RESTAURANTPRO_LAN_CIDRS", new String[] {"restaurantpro.security.lan-cidrs", "10.0.0.0/8"});
        FROZEN.put("RESTAURANTPRO_OIDC_ENABLED", new String[] {"restaurantpro.security.oidc.enabled", "true"});
        FROZEN.put("RESTAURANTPRO_OIDC_ISSUER_URI",
                new String[] {"restaurantpro.security.oidc.issuer-uri", "https://idp.example"});
        FROZEN.put("RESTAURANTPRO_OIDC_CLIENT_ID", new String[] {"restaurantpro.security.oidc.client-id", "rp"});
        FROZEN.put("RESTAURANTPRO_OIDC_CLIENT_SECRET_FILE",
                new String[] {"restaurantpro.security.oidc.client-secret-file", "/run/s"});
        FROZEN.put("RESTAURANTPRO_OIDC_DISPLAY_NAME",
                new String[] {"restaurantpro.security.oidc.display-name", "Entra"});
        FROZEN.put("RESTAURANTPRO_OIDC_SCOPES", new String[] {"restaurantpro.security.oidc.scopes", "openid,email"});
        FROZEN.put("RESTAURANTPRO_OIDC_TRUST_IDP_MFA",
                new String[] {"restaurantpro.security.oidc.trust-idp-mfa", "true"});
        FROZEN.put("RESTAURANTPRO_OIDC_POST_LOGIN_URI",
                new String[] {"restaurantpro.security.oidc.post-login-uri", "/bo/"});
        FROZEN.put("RESTAURANTPRO_ALERTS_BACKUP_STATUS_FILE",
                new String[] {"restaurantpro.alerts.backup-status-file", "/run/status.json"});
        FROZEN.put("RESTAURANTPRO_ALERTS_CASH_DIFFERENCE_THRESHOLD",
                new String[] {"restaurantpro.alerts.cash-difference-threshold", "2.50"});
        FROZEN.put("RESTAURANTPRO_METRICS_TOKEN_FILE", new String[] {"restaurantpro.metrics.token-file", "/run/t"});
    }

    @Test
    void everyFrozenEnvironmentNameSetsItsProperty() throws IOException {
        Map<String, Object> variables = new LinkedHashMap<>();
        FROZEN.forEach((name, property) -> variables.put(name, property[1]));
        StandardEnvironment environment = environmentWith(variables);

        FROZEN.forEach((name, property) -> assertThat(environment.getProperty(property[0]))
                .as(name + " → " + property[0]).isEqualTo(property[1]));
    }

    @Test
    void withoutThemThePropertiesHaveTheirDefaults() throws IOException {
        StandardEnvironment environment = environmentWith(Map.of());

        assertThat(environment.getProperty("restaurantpro.security.mfa.mode")).isEqualTo("OFF");
        assertThat(environment.getProperty("restaurantpro.security.mfa.step-up-max-age")).isEqualTo("PT15M");
        assertThat(environment.getProperty("restaurantpro.security.mfa.key-file")).isEmpty();
        assertThat(environment.getProperty("restaurantpro.security.lan-cidrs")).as("the D140 list")
                .isEqualTo("127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7,fe80::/10");
        assertThat(environment.getProperty("restaurantpro.security.oidc.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("restaurantpro.security.oidc.issuer-uri")).isEmpty();
        assertThat(environment.getProperty("restaurantpro.security.oidc.client-id")).isEmpty();
        assertThat(environment.getProperty("restaurantpro.security.oidc.client-secret-file")).isEmpty();
        assertThat(environment.getProperty("restaurantpro.security.oidc.display-name")).isEqualTo("SSO");
        assertThat(environment.getProperty("restaurantpro.security.oidc.scopes")).isEqualTo("openid");
        assertThat(environment.getProperty("restaurantpro.security.oidc.trust-idp-mfa")).isEqualTo("false");
        assertThat(environment.getProperty("restaurantpro.security.oidc.post-login-uri")).isEqualTo("/backoffice/");
        assertThat(environment.getProperty("restaurantpro.alerts.backup-status-file")).isEmpty();
        assertThat(environment.getProperty("restaurantpro.alerts.cash-difference-threshold")).isEqualTo("0");
        assertThat(environment.getProperty("restaurantpro.metrics.token-file")).isEmpty();
    }

    /** {@code application.yaml} over an environment holding only {@code variables}. */
    private static StandardEnvironment environmentWith(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addLast(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        for (PropertySource<?> yaml : new YamlPropertySourceLoader().load("application.yaml",
                new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(yaml);
        }
        return environment;
    }
}
