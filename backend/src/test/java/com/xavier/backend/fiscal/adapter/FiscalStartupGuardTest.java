package com.xavier.backend.fiscal.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.BackendApplication;
import com.xavier.backend.TestcontainersConfiguration;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A13 / B4: the real application refuses to start with the non-certified internal adapter unless allowed. */
class FiscalStartupGuardTest {

    @Test
    void theApplicationRefusesToStartWithTheInternalAdapterUnlessNonCertifiedIsAllowed() {
        PostgreSQLContainer postgres = TestcontainersConfiguration.postgres();

        assertThatThrownBy(() -> new SpringApplicationBuilder(BackendApplication.class)
                .properties(
                        "RESTAURANTPRO_DB_URL=" + postgres.getJdbcUrl(),
                        "RESTAURANTPRO_DB_USERNAME=" + postgres.getUsername(),
                        "RESTAURANTPRO_DB_PASSWORD=" + postgres.getPassword(),
                        "RESTAURANTPRO_MANAGEMENT_PORT=0",
                        "server.port=0")
                // command-line arguments outrank the test config that allows it
                .run("--restaurantpro.fiscal.allow-non-certified=false"))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("'internal' is NOT certified")
                .hasMessageContaining("restaurantpro.fiscal.allow-non-certified=true");
    }

    @Test
    void productionDefaultsDoNotAllowANonCertifiedAdapter() throws IOException {
        FiscalProperties defaults = bind(Map.of());

        assertThat(defaults.adapter()).isEqualTo("internal");
        assertThat(defaults.allowNonCertified()).isFalse();
    }

    @Test
    void theDeploymentEnvironmentVariableAllowsIt() throws IOException {
        assertThat(bind(Map.of("RESTAURANTPRO_FISCAL_ALLOW_NON_CERTIFIED", "true")).allowNonCertified()).isTrue();
    }

    /** Only the main application.yaml (production defaults) plus the given environment variables. */
    private static FiscalProperties bind(Map<String, Object> env) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addLast(new SystemEnvironmentPropertySource("env", env));
        new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"))
                .forEach(sources::addLast);
        return Binder.get(environment).bindOrCreate("restaurantpro.fiscal", FiscalProperties.class);
    }
}
