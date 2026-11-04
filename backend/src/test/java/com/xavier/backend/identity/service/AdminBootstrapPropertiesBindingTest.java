package com.xavier.backend.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The deployment (S5, D30) sets RESTAURANTPRO_BOOTSTRAP_ADMIN_USERNAME and
 * RESTAURANTPRO_BOOTSTRAP_ADMIN_INITIAL_PASSWORD_FILE. Relaxed binding maps them onto the settings by itself;
 * this pins that, together with application.yaml (which must not override them).
 */
class AdminBootstrapPropertiesBindingTest {

    @Test
    void theDeploymentEnvironmentVariablesBindToTheAdminBootstrapSettings() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.addLast(new SystemEnvironmentPropertySource("env", Map.of(
                "RESTAURANTPRO_BOOTSTRAP_ADMIN_USERNAME", "admin",
                "RESTAURANTPRO_BOOTSTRAP_ADMIN_INITIAL_PASSWORD_FILE", "/run/secrets/admin_initial_password")));
        new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"))
                .forEach(sources::addLast);

        AdminBootstrapProperties settings = Binder.get(environment)
                .bind("restaurantpro.bootstrap.admin", AdminBootstrapProperties.class).get();

        assertThat(settings).isEqualTo(
                new AdminBootstrapProperties("admin", Path.of("/run/secrets/admin_initial_password")));
    }
}
