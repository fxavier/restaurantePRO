package com.xavier.backend.organization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The deployment (S5, D30) sets RESTAURANTPRO_BOOTSTRAP_COMPANY_{NAME,TAX_ID,COUNTRY,CURRENCY}. Spring Boot's
 * environment-variable mapping binds TAX_ID to {@code tax-id} by itself, so application.yaml needs no entry;
 * this test pins that, together with application.yaml (which must not override the variables).
 */
class CompanyBootstrapPropertiesBindingTest {

    @Test
    void theDeploymentEnvironmentVariablesBindToTheBootstrapSettings() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.addLast(new SystemEnvironmentPropertySource("env", Map.of(
                "RESTAURANTPRO_BOOTSTRAP_COMPANY_NAME", "Restaurante Sol",
                "RESTAURANTPRO_BOOTSTRAP_COMPANY_TAX_ID", "400123456",
                "RESTAURANTPRO_BOOTSTRAP_COMPANY_COUNTRY", "MZ",
                "RESTAURANTPRO_BOOTSTRAP_COMPANY_CURRENCY", "MZN")));
        new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"))
                .forEach(sources::addLast);

        CompanyBootstrapProperties settings = Binder.get(environment)
                .bind("restaurantpro.bootstrap.company", CompanyBootstrapProperties.class).get();

        assertThat(settings).isEqualTo(new CompanyBootstrapProperties("Restaurante Sol", "400123456", "MZ", "MZN"));
    }
}
