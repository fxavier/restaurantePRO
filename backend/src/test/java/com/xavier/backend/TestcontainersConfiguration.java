package com.xavier.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
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

    /**
     * The pin as a compile-time constant: reading it does not initialise this class, so it does not start the
     * shared container (for tests that run their own database, e.g. {@code R1AcceptanceTest}).
     */
    public static final String POSTGRES_IMAGE_NAME = "postgres:18.6-alpine";

    /** Same image as {@code backend/compose.yaml} (D4: tests = production). */
    public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(POSTGRES_IMAGE_NAME);

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

    /**
     * The connection to the shared container, not the container itself: Spring Boot's Testcontainers lifecycle stops
     * every container bean of a closing context (also with {@code destroyMethod = ""}), and the test context cache
     * closes contexts while others still use the database ({@code SharedDatabaseSurvivesContextCloseTest}). The
     * container is started once above and stopped by Ryuk when the JVM exits.
     */
    @Bean
    JdbcConnectionDetails sharedPostgresConnectionDetails() {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return POSTGRES.getUsername();
            }

            @Override
            public String getPassword() {
                return POSTGRES.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return POSTGRES.getJdbcUrl();
            }
        };
    }
}
