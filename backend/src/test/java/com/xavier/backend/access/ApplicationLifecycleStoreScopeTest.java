package com.xavier.backend.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.DatabaseRoles;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * D171: an application started or closed by a test body (a simulated restart, {@code new SpringApplicationBuilder(...)
 * .run()}) runs its startup and shutdown code with production semantics, although it runs on the marked JUnit thread:
 * runners, the ready event, the closed event and bean destruction see no fixture fallback. The test body around them
 * keeps it.
 */
class ApplicationLifecycleStoreScopeTest {

    static final StoreScopes SCOPES = StoreScopeTestSupport.scopes();
    static final Map<String, String> SEEN = new ConcurrentHashMap<>();
    static final String SCHEMA = "rls_scratch_lifecycle";
    static final String TABLE = SCHEMA + ".item";
    /** D189: whether the bootstrap-tenant fixture fallback applied, per lifecycle step. */
    static final Map<String, Boolean> TENANT_SEEN = new ConcurrentHashMap<>();

    @BeforeAll
    static void scratchTable() {
        dropScratch();
        OwnerDatabase.shared().tx(owner -> {
            owner.sql("CREATE SCHEMA " + SCHEMA).update();
            owner.sql("CREATE TABLE " + TABLE + " (id uuid PRIMARY KEY, store_id uuid NOT NULL, label text)").update();
            owner.sql("SELECT shared.rls_protect_store_table(CAST(? AS regclass))").param(TABLE).query().listOfRows();
            owner.sql("INSERT INTO " + TABLE + " VALUES (?, ?, 'a'), (?, ?, 'b')").param(UUID.randomUUID())
                    .param(UUID.randomUUID()).param(UUID.randomUUID()).param(UUID.randomUUID()).update();
        });
    }

    @AfterAll
    static void dropScratch() {
        OwnerDatabase.shared().tx(owner -> {
            owner.sql("DELETE FROM shared.rls_protected_table WHERE table_name::text LIKE ?").param(SCHEMA + ".%")
                    .update();
            owner.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
        });
    }

    @BeforeEach
    void forget() {
        SEEN.clear();
        TENANT_SEEN.clear();
    }

    @Test
    void anApplicationStartedAndClosedInATestBodyHasProductionSemantics() {
        ConfigurableApplicationContext application = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE).run();
        String afterRun = SCOPES.current().origin();
        application.close();

        assertThat(SEEN).containsOnly(Map.entry("runner", "none"), Map.entry("ready", "none"),
                Map.entry("closed", "none"), Map.entry("destroy", "none"));
        assertThat(TENANT_SEEN).as("no bootstrap-tenant fallback during startup and shutdown either")
                .containsOnly(Map.entry("runner", false), Map.entry("ready", false), Map.entry("closed", false),
                        Map.entry("destroy", false));
        assertThat(TenantTestSupport.isFixtureThread()).as("the test body after close()").isTrue();
        assertThat(afterRun).as("the test body after run()").isEqualTo("test-fixture");
        assertThat(SCOPES.current().origin()).as("the test body after close()").isEqualTo("test-fixture");
    }

    /** A child context (Boot's management context on its own port) closes inside its parent's close. */
    @Test
    void fixtureSqlAfterTheCloseOfAnApplicationWithAChildContextSeesEveryStore() {
        ConfigurableApplicationContext application = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE).run();
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        child.setParent(application);
        child.refresh();
        application.addApplicationListener((ContextClosedEvent event) -> {
            if (event.getApplicationContext() == application) {
                child.close();
            }
        });
        assertThat(labels()).as("after run()").isEqualTo("a,b");

        application.close();

        assertThat(labels()).as("after close()").isEqualTo("a,b");
    }

    @Test
    void fixtureSqlAroundTwoOverlappingApplicationsSeesEveryStore() {
        ConfigurableApplicationContext first = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE).run();
        ConfigurableApplicationContext second = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE).run();
        assertThat(labels()).as("both running").isEqualTo("a,b");

        first.close();
        assertThat(labels()).as("the first closed").isEqualTo("a,b");
        second.close();

        assertThat(labels()).as("both closed").isEqualTo("a,b");
    }

    @Test
    void aFailedStartRestoresTheTestBodysFallback() {
        try {
            new SpringApplicationBuilder(Failing.class).web(WebApplicationType.NONE).run();
        } catch (IllegalStateException expected) {
            // the start failed, as intended
        }

        assertThat(SCOPES.current().origin()).isEqualTo("test-fixture");
    }

    @Test
    void aRefreshThatFailsRestoresTheTestBodysFallback() {
        try {
            new SpringApplicationBuilder(Unbuildable.class).web(WebApplicationType.NONE).run();
        } catch (RuntimeException expected) {
            // the refresh failed, as intended: beans are destroyed with no closed event
        }

        assertThat(SCOPES.current().origin()).isEqualTo("test-fixture");
    }

    /**
     * The labels the application role sees in a transaction bound to {@link #SCOPES}' current scope, as the
     * production binding writes it (D178).
     */
    private static String labels() {
        StoreScope scope = SCOPES.current();
        var postgres = TestcontainersConfiguration.postgres();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), DatabaseRoles.APP_ROLE,
                DatabaseRoles.appPassword())) {
            connection.setAutoCommit(false);
            JdbcClient app = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            app.sql("SELECT set_config('app.all_stores', ?, true), set_config('app.store_ids', ?, true), "
                    + "set_config('app.scope_origin', ?, true)").param(scope.allStoresSetting())
                    .param(scope.storeIdsLiteral()).param(scope.origin()).query().listOfRows();
            String labels = String.join(",", app.sql("SELECT label FROM " + TABLE + " ORDER BY label")
                    .query(String.class).list());
            connection.rollback();
            return labels;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void seen(String step) {
        SEEN.put(step, SCOPES.current().origin());
        TENANT_SEEN.put(step, TenantTestSupport.FALLBACK.whenUnbound().isPresent());
    }

    @Configuration(proxyBeanMethods = false)
    static class Unbuildable {

        @Bean
        DisposableBean built() {
            return () -> seen("destroy");
        }

        @Bean
        Object unbuildable(DisposableBean built) {
            throw new IllegalStateException("refresh refused");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Application {

        @Bean
        ApplicationRunner runner() {
            return args -> seen("runner");
        }

        @Bean
        DisposableBean destroyed() {
            return () -> seen("destroy");
        }

        @EventListener
        void ready(ApplicationReadyEvent event) {
            seen("ready");
        }

        @EventListener
        void closed(ContextClosedEvent event) {
            seen("closed");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Failing {

        @Bean
        ApplicationRunner failing() {
            return args -> {
                throw new IllegalStateException("start refused");
            };
        }
    }
}
