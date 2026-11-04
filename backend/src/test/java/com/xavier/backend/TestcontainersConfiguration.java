package com.xavier.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One real PostgreSQL for the whole test JVM (spec §12, D4, D11: never H2).
 *
 * <p>Usage: {@code @SpringBootTest @Import(TestcontainersConfiguration.class)} or
 * {@code @ApplicationModuleTest @Import(TestcontainersConfiguration.class)}. The container is started
 * once and shared by every Spring context, so different context configurations do not start new
 * databases. Tests must therefore not assume an empty database: use fresh UUIDs/keys per test.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Same image as {@code backend/compose.yaml} (D4: tests = production). */
    public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.6-alpine");

    // The container has no TLS; skipping the SSL probe avoids a flaky "error setting up the SSL connection".
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withUrlParam("sslmode", "disable");

    static {
        POSTGRES.start();
    }

    // destroyMethod = "": closing one cached context must not stop the container the others use.
    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }
}
