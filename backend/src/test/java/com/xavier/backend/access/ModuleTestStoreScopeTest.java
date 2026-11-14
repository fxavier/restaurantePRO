package com.xavier.backend.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.StoreScopeTestAutoConfigurationTest;
import com.xavier.backend.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * D171 in a module test: {@code @ApplicationModuleTest} drops the bean definitions of every module it does not
 * include, so a context without {@code config} lost the store-scope hook and advisor ({@code StoreScopeConfiguration})
 * and every read of a protected table raised {@code RS001}. The harness restores them: this context includes only
 * access and shared, and still gets production semantics for jobs, listeners and requests, and the fixture fallback
 * for SQL in the test body. On a scratch table protected by {@code shared.rls_protect_store_table}.
 */
@ApplicationModuleTest(extraIncludes = "shared")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ModuleTestStoreScopeTest.Probe.class})
class ModuleTestStoreScopeTest {

    static final String STORE_A = "0190a000-0000-7000-8000-0000000000ab";
    static final UUID STORE_B = UUID.fromString("0190a000-0000-7000-8000-0000000000bc");
    static final String SCHEMA = "rls_scratch_module_test";
    static final String TABLE = SCHEMA + ".item";
    static final String PATH = "/api/v1/backoffice/test-module-scope/labels";
    static final String READ = "SELECT current_setting('app.scope_origin', true) || ':' || coalesce(string_agg(label, ',' "
            + "ORDER BY label), '') FROM " + TABLE;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Jobs jobs;
    @Autowired
    ApplicationEventPublisher events;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ApplicationContext context;

    @BeforeAll
    static void scratchTable() {
        dropScratch();
        OwnerDatabase.shared().tx(owner -> {
            owner.sql("CREATE SCHEMA " + SCHEMA).update();
            owner.sql("CREATE TABLE " + TABLE + " (id uuid PRIMARY KEY, store_id uuid NOT NULL, label text)").update();
            owner.sql("SELECT shared.rls_protect_store_table(CAST(? AS regclass))").param(TABLE).query().listOfRows();
            owner.sql("INSERT INTO " + TABLE + " VALUES (?, ?, 'a'), (?, ?, 'b')").param(UUID.randomUUID())
                    .param(UUID.fromString(STORE_A)).param(UUID.randomUUID()).param(STORE_B).update();
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

    @Test
    void theModuleContextHasExactlyOneHookAndOneAdvisor() {
        assertThat(StoreScopeTestAutoConfigurationTest.storeScopePlumbing(context))
                .containsExactly("StoreScopeDataSourcePostProcessor", "StoreScopeAdvisor");
    }

    @Test
    void fixtureSqlInTheTestBodySeesEveryStore() {
        assertThat(jdbc.sql(READ).query(String.class).single()).isEqualTo("test-fixture:a,b");
    }

    @Test
    void anAllStoresJobRunsInItsSystemScopeOnAnyThread() throws Exception {
        assertThat(CompletableFuture.supplyAsync(jobs::reservations).get(10, TimeUnit.SECONDS))
                .isEqualTo("system:RESERVATIONS:a,b");
    }

    @Test
    void aNoStoresJobSeesNoStoreRows() throws Exception {
        assertThat(CompletableFuture.supplyAsync(jobs::housekeeping).get(10, TimeUnit.SECONDS))
                .isEqualTo("system:HOUSEKEEPING:");
    }

    @Test
    void aListenerRunsInTheScopeOfItsEventsStore() throws Exception {
        Jobs.heard = new CompletableFuture<>();

        tx.executeWithoutResult(status -> events.publishEvent(new LabelRequested(STORE_B)));

        assertThat(Jobs.heard.get(10, TimeUnit.SECONDS)).isEqualTo("listener:Jobs.on:b");
    }

    @Test
    @WithActor(permissions = Permission.ORDER_VIEW, storeId = STORE_A)
    void aRequestOfAnActorSeesOnlyItsStores() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(content().json("[\"a\"]"));
    }

    @Test
    @WithMockUser
    void anActorlessRequestHasNoStoreScope() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("STORE_SCOPE_MISSING"));
    }

    record LabelRequested(UUID storeId) {
    }

    @Component
    static class Jobs {

        static volatile CompletableFuture<String> heard = new CompletableFuture<>();

        private final JdbcClient jdbc;

        Jobs(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @SystemScoped(SystemScope.RESERVATIONS)
        public String reservations() {
            return read();
        }

        @SystemScoped(SystemScope.HOUSEKEEPING)
        public String housekeeping() {
            return read();
        }

        @ApplicationModuleListener
        public void on(LabelRequested event) {
            try {
                heard.complete(read());
            } catch (RuntimeException e) {
                heard.completeExceptionally(e);
            }
        }

        private String read() {
            return jdbc.sql(READ).query(String.class).single();
        }
    }

    @RestController
    static class Labels {

        private final JdbcClient jdbc;

        Labels(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @GetMapping(PATH)
        List<String> labels() {
            return jdbc.sql("SELECT label FROM " + TABLE + " ORDER BY label").query(String.class).list();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({Jobs.class, Labels.class})
    static class Probe {
    }
}
