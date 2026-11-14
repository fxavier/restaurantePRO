package com.xavier.backend;

import com.xavier.backend.access.FixtureFreeRequests;
import com.xavier.backend.access.StoreScopeFallback;
import com.xavier.backend.access.StoreScopeTestSupport;
import com.xavier.backend.config.persistence.StoreScopeConfiguration;
import org.flywaydb.core.Flyway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
 *
 * <p>Roles (ADR 0039, D163, D171): the container is provisioned with {@code deploy/postgres/provision-roles.sql}, the
 * production file, with passwords generated per JVM. Every context's pool connects as {@value #APP_ROLE} (row-level
 * security binds it) and Flyway as {@value #OWNER_ROLE}. Privileged fixtures ({@code TRUNCATE}, {@code DISABLE TRIGGER},
 * {@code ANALYZE}, DDL) go through {@link OwnerDatabase}. The store-scope hook is imported here so module tests get it
 * too, and the {@link StoreScopeFallback} gives fixture code on the JUnit thread all stores ({@link
 * StoreScopeTestSupport}).
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(StoreScopeConfiguration.class)
public class TestcontainersConfiguration {

    public static final String APP_ROLE = DatabaseRoles.APP_ROLE;
    public static final String OWNER_ROLE = DatabaseRoles.OWNER_ROLE;

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
        provision(POSTGRES);
        migrate();
    }

    /**
     * Migrates the shared database once, as the owner and with {@code application.yaml}'s settings, so tests without a
     * Spring context (the store-scope suites) find the schema; every context's Flyway then finds it up to date.
     */
    private static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), OWNER_ROLE, ownerPassword())
                .locations("classpath:db/migration").outOfOrder(true).load().migrate();
    }

    /** As {@link DatabaseRoles#provision}: for tests that start their own database. */
    public static void provision(PostgreSQLContainer container) {
        DatabaseRoles.provision(container);
    }

    /** The application role's password in every container of this JVM. */
    public static String appPassword() {
        return DatabaseRoles.appPassword();
    }

    /** The owner role's password in every container of this JVM. */
    public static String ownerPassword() {
        return DatabaseRoles.ownerPassword();
    }

    /** Fixture SQL on the JUnit thread sees every store; any other thread, and a strict test, gets production's. */
    @Bean
    @Primary
    StoreScopeFallback testFixtureStoreScopeFallback() {
        return StoreScopeTestSupport.FIXTURE_FALLBACK;
    }

    /** A request dispatched by MockMvc on the JUnit thread does not inherit the fixture fallback. */
    @Bean
    FixtureFreeRequests fixtureFreeRequests() {
        return new FixtureFreeRequests();
    }

    @Bean
    OwnerDatabase ownerDatabase() {
        return OwnerDatabase.shared();
    }

    /** For tests that start a second application (e.g. a simulated restart) on the same database. */
    public static PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    /**
     * The connection to the shared container, not the container itself: Spring Boot's Testcontainers lifecycle stops
     * every container bean of a closing context (also with {@code destroyMethod = ""}), and the test context cache
     * closes contexts while others still use the database ({@code SharedDatabaseSurvivesContextCloseTest}). The
     * container is started once above and stopped by Ryuk when the JVM exits. The pool is the application role.
     */
    @Bean
    JdbcConnectionDetails sharedPostgresConnectionDetails() {
        return DatabaseRoles.applicationConnection(POSTGRES);
    }

    /**
     * Flyway's own connection to the shared container, as the owner role (D163): Flyway migrates outside the
     * application's pool, which a test may size down to one connection ({@code IdempotencyStepsSingleConnectionTest}).
     */
    @Bean
    FlywayConnectionDetails sharedPostgresFlywayConnectionDetails() {
        return DatabaseRoles.ownerConnection(POSTGRES);
    }
}
