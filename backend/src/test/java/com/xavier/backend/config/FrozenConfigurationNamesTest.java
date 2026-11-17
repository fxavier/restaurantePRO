package com.xavier.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The R5 PRD freezes environment names (Compose, S62) that Spring's relaxed binding does not map to their properties
 * (e.g. {@code RESTAURANTPRO_ALERTS_CASH_DIFFERENCE_THRESHOLD} is not
 * {@code restaurantpro.alerts.cash-difference-threshold}). {@code application.yaml} binds each one with an explicit placeholder; this test sets every frozen name as an environment variable would and
 * reads the property, and reads the defaults when none is set.
 */
class FrozenConfigurationNamesTest {

    /** Frozen environment name → property it must set, with a non-default value. */
    static final Map<String, String[]> FROZEN = new LinkedHashMap<>();

    static {
        FROZEN.put("RESTAURANTPRO_ALERTS_BACKUP_STATUS_FILE",
                new String[] {"restaurantpro.alerts.backup-status-file", "/run/status.json"});
        FROZEN.put("RESTAURANTPRO_ALERTS_CASH_DIFFERENCE_THRESHOLD",
                new String[] {"restaurantpro.alerts.cash-difference-threshold", "2.50"});
        FROZEN.put("RESTAURANTPRO_METRICS_TOKEN_FILE", new String[] {"restaurantpro.metrics.token-file", "/run/t"});
        // RLS (ADR 0039, D163, D170)
        FROZEN.put("RESTAURANTPRO_DB_USERNAME", new String[] {"spring.datasource.username", "pool_role"});
        FROZEN.put("RESTAURANTPRO_FLYWAY_USERNAME", new String[] {"spring.flyway.user", "migration_role"});
        FROZEN.put("RESTAURANTPRO_FLYWAY_PASSWORD", new String[] {"spring.flyway.password", "not-a-secret"});
        FROZEN.put("RESTAURANTPRO_STORE_SCOPE_ENFORCEMENT",
                new String[] {"restaurantpro.store-scope.enforcement", "PERMISSIVE"});
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

        assertThat(environment.getProperty("restaurantpro.alerts.backup-status-file")).isEmpty();
        assertThat(environment.getProperty("restaurantpro.alerts.cash-difference-threshold")).isEqualTo("0");
        assertThat(environment.getProperty("restaurantpro.metrics.token-file")).isEmpty();
        assertThat(environment.getProperty("spring.datasource.username")).as("D163: the pool is the application role")
                .isEqualTo("restaurantpro_app");
        assertThat(environment.getProperty("spring.flyway.user")).as("D163: Flyway runs as the owner")
                .isEqualTo("restaurantpro_owner");
        assertThat(environment.getProperty("spring.flyway.password")).isEmpty();
        assertThat(environment.getProperty("restaurantpro.store-scope.enforcement")).isEqualTo("ENFORCE");
    }

    /**
     * R6 (S72): the BackOffice signs in through Keycloak and the API is stateless with no CSRF, so the local MFA, the
     * LAN rule, the one OIDC provider, the server session and Swagger's CSRF echo have no reader left: none is
     * configured, even when its old environment name is set.
     */
    @Test
    void theRetiredSessionMfaOidcAndSwaggerCsrfPropertiesAreGone() throws IOException {
        StandardEnvironment environment = environmentWith(Map.of("RESTAURANTPRO_MFA_MODE", "SENSITIVE",
                "RESTAURANTPRO_OIDC_ENABLED", "true", "RESTAURANTPRO_LAN_CIDRS", "10.0.0.0/8"));

        assertThat(List.of("restaurantpro.security.mfa.mode", "restaurantpro.security.mfa.step-up-max-age",
                "restaurantpro.security.mfa.key-file", "restaurantpro.security.lan-cidrs",
                "restaurantpro.security.oidc.enabled", "restaurantpro.security.oidc.issuer-uri",
                "restaurantpro.security.oidc.client-id", "restaurantpro.security.oidc.client-secret-file",
                "restaurantpro.security.oidc.display-name", "restaurantpro.security.oidc.scopes",
                "restaurantpro.security.oidc.trust-idp-mfa", "restaurantpro.security.oidc.post-login-uri",
                "spring.session.jdbc.table-name", "spring.session.jdbc.initialize-schema",
                "server.servlet.session.cookie.secure", "server.servlet.session.cookie.http-only",
                "server.servlet.session.cookie.same-site", "springdoc.swagger-ui.csrf.enabled"))
                .filteredOn(environment::containsProperty).isEmpty();
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
