package com.xavier.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The PgBouncer drill (A83, D178, D188): the application's pool connects through PgBouncer in transaction pooling
 * mode with a single server connection (every transaction of every client runs on the same backend, the worst case
 * for leaks), settings as {@code deploy/drills/pgbouncer/pgbouncer.ini}. Through the real
 * {@code TenantBindingTransactionManager}: alternating tenants never see each other's rows, and after a commit or a
 * rollback the server connection carries no tenant (a protected read raises RS003). {@code LISTEN} does not survive
 * transaction pooling (the notice reaches a server connection no client is linked to), so {@code ChangeNotifier}'s
 * listening connection must bypass the pooler (it already opens its own connection; it needs its own URL).
 */
@SpringBootTest(properties = "restaurantpro.test.fixture-checkouts=false") // production's pool: no checkout write
@Import({TestcontainersConfiguration.class, PgBouncerTransactionModeTest.ThroughPgBouncer.class})
@StrictTenantScope
class PgBouncerTransactionModeTest {

    static final String PGBOUNCER_IMAGE = "edoburu/pgbouncer:v1.24.1-p1";
    static final String STORE_CODE = "PGB-1";

    @Autowired
    DataSource pool;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    TenantScopes tenants;

    static UUID storeA;
    static UUID storeB;

    @BeforeAll
    static void storesWithTheSameCodeInTwoTenants() {
        TenantTestSupport.ensureTenants();
        storeA = store(TenantTestSupport.A);
        storeB = store(TenantTestSupport.B);
    }

    @Test
    void alternatingTenantsOnOneServerConnectionNeverMix() {
        JdbcClient jdbc = JdbcClient.create(pool);
        Set<Integer> backends = new HashSet<>();

        for (int round = 0; round < 40; round++) {
            UUID tenant = round % 2 == 0 ? TenantTestSupport.A : TenantTestSupport.B;
            UUID expected = tenant.equals(TenantTestSupport.A) ? storeA : storeB;
            List<UUID> visible = inTenant(tenant, () -> {
                backends.add(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                assertThat(jdbc.sql("SELECT current_setting('app.tenant_id')").query(String.class).single())
                        .isEqualTo(tenant.toString());
                return jdbc.sql("SELECT id FROM organization.store WHERE code = ?").param(STORE_CODE)
                        .query(UUID.class).list();
            });

            assertThat(visible).as("round %d in %s", round, tenant).containsExactly(expected);
        }
        assertThat(backends).as("PgBouncer served every transaction from one server connection").hasSize(1);
    }

    @Test
    void afterACommitOrARollbackTheServerConnectionCarriesNoTenant() {
        JdbcClient jdbc = JdbcClient.create(pool);
        inTenant(TenantTestSupport.A, () -> jdbc.sql("SELECT count(*) FROM organization.store").query(Long.class)
                .single());
        assertUnbound(jdbc);

        try {
            inTenant(TenantTestSupport.B, () -> {
                jdbc.sql("SELECT count(*) FROM organization.store").query(Long.class).single();
                throw new IllegalStateException("roll back");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        assertUnbound(jdbc);
    }

    @Test
    void listenDoesNotSurviveTransactionPoolingSoTheNotifierMustBypassThePooler() throws Exception {
        PostgreSQLContainer postgres = TestcontainersConfiguration.postgres();
        try (Connection throughPooler = DriverManager.getConnection(ThroughPgBouncer.url(),
                TestcontainersConfiguration.APP_ROLE, TestcontainersConfiguration.appPassword());
             Connection direct = DriverManager.getConnection(postgres.getJdbcUrl(),
                     TestcontainersConfiguration.APP_ROLE, TestcontainersConfiguration.appPassword());
             Connection notifier = DriverManager.getConnection(postgres.getJdbcUrl(),
                     TestcontainersConfiguration.APP_ROLE, TestcontainersConfiguration.appPassword())) {
            String channel = "pgbouncer_drill_" + UUID.randomUUID().toString().replace("-", "");
            listen(throughPooler, channel);
            listen(direct, channel);

            try (Statement statement = notifier.createStatement()) {
                statement.execute("NOTIFY " + channel + ", 'drill'");
            }

            assertThat(notifications(direct, 5_000)).as("a direct connection receives it").isPositive();
            assertThat(notifications(throughPooler, 2_000)).as("through the pooler it is lost").isZero();
        }
    }

    private void assertUnbound(JdbcClient jdbc) {
        assertThat(jdbc.sql("SELECT coalesce(current_setting('app.tenant_id', true), '')").query(String.class)
                .single()).as("outside a transaction, on the pooled server connection").isEmpty();
        String bound = new TransactionTemplate(transactions).execute(status -> jdbc
                .sql("SELECT coalesce(current_setting('app.tenant_id', true), '')").query(String.class).single());
        assertThat(bound).isEmpty();
        AppRoleSession.assertRefused(() -> new TransactionTemplate(transactions).execute(status -> jdbc
                        .sql("SELECT count(*) FROM organization.store").query(Long.class).single()),
                com.xavier.backend.shared.persistence.StoreScopeViolations.Kind.TENANT_MISSING);
    }

    private <T> T inTenant(UUID tenant, java.util.function.Supplier<T> work) {
        return tenants.call(tenant, SystemScope.PROVISIONING,
                () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    private static UUID store(UUID tenant) {
        UUID existing = OwnerDatabase.shared().jdbc().sql("SELECT id FROM organization.store WHERE tenant_id = ? "
                + "AND code = ?").param(tenant).param(STORE_CODE).query(UUID.class).optional().orElse(null);
        if (existing != null) {
            return existing;
        }
        UUID id = UUID.randomUUID();
        OwnerDatabase.shared().jdbc().sql("INSERT INTO organization.store (id, tenant_id, code, name, timezone, "
                        + "active, version, created_at, updated_at) VALUES (?, ?, ?, 'Drill', 'Africa/Maputo', true, 0, "
                        + "now(), now())")
                .param(id).param(tenant).param(STORE_CODE).update();
        return id;
    }

    private static void listen(Connection connection, String channel) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("LISTEN " + channel);
        }
    }

    /** {@code PGConnection.getNotifications(timeout)}: waits up to {@code millis} for the first notice. */
    private static int notifications(Connection connection, int millis) throws Exception {
        Object pg = connection.unwrap(Class.forName("org.postgresql.PGConnection"));
        Method poll = pg.getClass().getMethod("getNotifications", int.class);
        Object[] received = (Object[]) poll.invoke(pg, millis);
        return received == null ? 0 : received.length;
    }

    /**
     * The application pool through PgBouncer ({@code @Primary} over the shared container's direct connection);
     * Flyway and the owner fixtures stay direct.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ThroughPgBouncer {

        private static final GenericContainer<?> PGBOUNCER = start();

        private static GenericContainer<?> start() {
            PostgreSQLContainer postgres = TestcontainersConfiguration.postgres();
            String host = postgres.getContainerInfo().getNetworkSettings().getNetworks().get("bridge").getIpAddress();
            GenericContainer<?> pgbouncer = new GenericContainer<>(PGBOUNCER_IMAGE)
                    .withEnv("DB_HOST", host).withEnv("DB_PORT", "5432")
                    .withEnv("DB_USER", TestcontainersConfiguration.APP_ROLE)
                    .withEnv("DB_PASSWORD", TestcontainersConfiguration.appPassword())
                    .withEnv("AUTH_TYPE", "scram-sha-256")
                    // as deploy/drills/pgbouncer/pgbouncer.ini, except the single server connection of the drill
                    .withEnv("POOL_MODE", "transaction").withEnv("DEFAULT_POOL_SIZE", "1")
                    .withEnv("MAX_CLIENT_CONN", "200").withEnv("MAX_PREPARED_STATEMENTS", "200")
                    .withEnv("IGNORE_STARTUP_PARAMETERS", "extra_float_digits")
                    .withExposedPorts(5432).waitingFor(Wait.forListeningPort());
            pgbouncer.start();
            return pgbouncer;
        }

        static String url() {
            return "jdbc:postgresql://" + PGBOUNCER.getHost() + ":" + PGBOUNCER.getMappedPort(5432) + "/"
                    + TestcontainersConfiguration.postgres().getDatabaseName() + "?sslmode=disable";
        }

        @Bean
        @Primary
        JdbcConnectionDetails pgBouncerConnection() {
            return new JdbcConnectionDetails() {
                @Override
                public String getUsername() {
                    return TestcontainersConfiguration.APP_ROLE;
                }

                @Override
                public String getPassword() {
                    return TestcontainersConfiguration.appPassword();
                }

                @Override
                public String getJdbcUrl() {
                    return url();
                }
            };
        }
    }
}
