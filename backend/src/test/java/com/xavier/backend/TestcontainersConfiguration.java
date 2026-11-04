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
 *
 * <p>Connection budget: Spring caches every distinct test context and each keeps its Hikari pool open
 * against this one database. With PostgreSQL's default {@code max_connections=100} and Hikari's default
 * pool of 10, a dozen cached contexts exhaust the server ({@code FATAL: sorry, too many clients already},
 * SQLSTATE 53300). So the server allows {@value #MAX_CONNECTIONS} connections and every test context
 * uses at most {@value #POOL_SIZE}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Same image as {@code backend/compose.yaml} (D4: tests = production). */
    public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.6-alpine");

    static final int MAX_CONNECTIONS = 300;
    static final int POOL_SIZE = 5;

    // The container has no TLS; skipping the SSL probe avoids a flaky "error setting up the SSL connection".
    // fsync=off: the database is thrown away after the run, so durability buys nothing and costs time.
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withUrlParam("sslmode", "disable")
            .withCommand("postgres", "-c", "max_connections=" + MAX_CONNECTIONS, "-c", "fsync=off");

    static {
        // A JVM system property reaches every context, including ones built with SpringApplicationBuilder
        // (EventRepublicationTest's restart), and outranks application.yaml without shadowing it.
        System.setProperty("spring.datasource.hikari.maximum-pool-size", String.valueOf(POOL_SIZE));
        POSTGRES.start();
    }

    /** For tests that start a second application (e.g. a simulated restart) on the same database. */
    public static PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    // destroyMethod = "": closing one cached context must not stop the container the others use.
    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }
}
