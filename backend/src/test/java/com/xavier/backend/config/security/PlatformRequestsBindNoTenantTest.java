package com.xavier.backend.config.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * D181: a platform operator's request runs with no tenant at all, explicitly, whatever a fallback would bind: its
 * transactions bind {@code app.tenant_id = ''} (the platform SQL functions refuse a bound tenant, S75's TN004). Here
 * on the JUnit thread, whose fixture fallback would otherwise bind the bootstrap tenant.
 */
@SecurityHttpTest
@Import(PlatformRequestsBindNoTenantTest.Probe.class)
class PlatformRequestsBindNoTenantTest {

    @Autowired
    MockMvc mvc;

    @Test
    void aPlatformOperatorsTransactionBindsNoTenant() throws Exception {
        mvc.perform(get("/api/v1/platform/tenant-binding-probe").with(TestJwtFactory.support("operator-1",
                        Instant.now()).claim("realm_access", Map.of("roles", List.of("platform-operator"))).bearer()))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    /** Reports the tenant its transaction is bound to, between brackets. */
    @RestController
    static class Probe {

        private final JdbcClient jdbc;
        private final TransactionTemplate transaction;

        Probe(JdbcClient jdbc, PlatformTransactionManager transactions) {
            this.jdbc = jdbc;
            this.transaction = new TransactionTemplate(transactions);
        }

        @GetMapping("/api/v1/platform/tenant-binding-probe")
        String binding() {
            return "[" + transaction.execute(status -> jdbc.sql("SELECT current_setting('app.tenant_id', true)")
                    .query(String.class).single()) + "]";
        }
    }
}
