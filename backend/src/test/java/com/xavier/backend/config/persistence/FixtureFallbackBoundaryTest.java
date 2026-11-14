package com.xavier.backend.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StoreScopeTestSupport;
import com.xavier.backend.access.WithActor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * D171 boundary of the fixture fallback: it serves fixture code in the test body only. A request dispatched by MockMvc
 * and the Spring context refresh run with production semantics, so an actor-less request reading a protected table
 * gets {@code STORE_SCOPE_MISSING} as it would in production. On a scratch table protected by
 * {@code shared.rls_protect_store_table}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixtureFallbackBoundaryTest.Probe.class})
class FixtureFallbackBoundaryTest {

    static final String STORE_A = "0190a000-0000-7000-8000-0000000000aa";
    static final String PATH = "/api/v1/backoffice/test-fixture-boundary/labels";
    static RlsScratch scratch;
    static volatile Boolean refreshedOnAFixtureThread;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeAll
    static void scratchTable() {
        scratch = RlsScratch.create("fixture_boundary");
        scratch.insert(UUID.fromString(STORE_A), "a");
        scratch.insert(UUID.randomUUID(), "b");
    }

    @AfterAll
    static void dropScratch() {
        scratch.close();
    }

    @Test
    void theContextRefreshDoesNotRunOnAFixtureThread() {
        assertThat(refreshedOnAFixtureThread).isFalse();
    }

    @Test
    @WithMockUser
    void anActorlessRequestHasNoStoreScopeWhileFixtureSqlInTheTestSeesEveryStore() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("STORE_SCOPE_MISSING"));

        assertThat(jdbc.sql("SELECT label FROM " + scratch.table() + " ORDER BY label").query(String.class).list())
                .containsExactly("a", "b");
    }

    @Test
    @WithActor(permissions = Permission.ORDER_VIEW, storeId = STORE_A)
    void aRequestOfAnActorSeesItsStores() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(content().json("[\"a\"]"));
    }

    @RestController
    static class Labels {

        private final JdbcClient jdbc;

        Labels(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @GetMapping(PATH)
        List<String> labels() {
            return jdbc.sql("SELECT label FROM " + scratch.table() + " ORDER BY label").query(String.class).list();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(Labels.class)
    static class Probe {

        @Bean
        Object refreshThreadProbe() {
            refreshedOnAFixtureThread = StoreScopeTestSupport.isFixtureThread();
            return new Object();
        }
    }
}
