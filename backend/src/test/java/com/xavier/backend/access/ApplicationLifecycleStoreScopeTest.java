package com.xavier.backend.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.DatabaseRoles;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.config.persistence.StoreScopeEnforcement;
import com.xavier.backend.config.persistence.StoreScopedDataSource;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
    /** The application role through the production connection hook, scoped by {@link #SCOPES}. */
    static JdbcClient app;

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
        var postgres = TestcontainersConfiguration.postgres();
        app = JdbcClient.create(new StoreScopedDataSource(new DriverManagerDataSource(postgres.getJdbcUrl(),
                DatabaseRoles.APP_ROLE, DatabaseRoles.appPassword()), SCOPES, StoreScopeEnforcement.ENFORCE));
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
    }

    @Test
    void anApplicationStartedAndClosedInATestBodyHasProductionSemantics() {
        ConfigurableApplicationContext application = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE).run();
        String afterRun = SCOPES.current().origin();
        application.close();

        assertThat(SEEN).containsOnly(Map.entry("runner", "none"), Map.entry("ready", "none"),
                Map.entry("closed", "none"), Map.entry("destroy", "none"));
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

    private static String labels() {
        return String.join(",", app.sql("SELECT label FROM " + TABLE + " ORDER BY label").query(String.class).list());
    }

    @Configuration(proxyBeanMethods = false)
    static class Unbuildable {

        @Bean
        DisposableBean built() {
            return () -> SEEN.put("destroy", SCOPES.current().origin());
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
            return args -> SEEN.put("runner", SCOPES.current().origin());
        }

        @Bean
        DisposableBean destroyed() {
            return () -> SEEN.put("destroy", SCOPES.current().origin());
        }

        @EventListener
        void ready(ApplicationReadyEvent event) {
            SEEN.put("ready", SCOPES.current().origin());
        }

        @EventListener
        void closed(ContextClosedEvent event) {
            SEEN.put("closed", SCOPES.current().origin());
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
