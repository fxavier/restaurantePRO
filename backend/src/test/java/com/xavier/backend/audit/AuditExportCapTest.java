package com.xavier.backend.audit;

import static com.xavier.backend.audit.AuditFixtures.as;
import static com.xavier.backend.audit.AuditFixtures.uniqueAction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** D82 row cap, proven with a cap of 2 instead of writing 100 001 rows (the default is checked in AuditExportTest). */
@SpringBootTest(properties = "restaurantpro.audit.export.max-rows=2")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditExportCapTest {

    static final Instant T0 = Instant.parse("2022-09-01T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void anExportOverTheCapIsRefusedAndNotRecorded() throws Exception {
        AuditFixtures audit = new AuditFixtures(jdbc);
        UUID store = UUID.randomUUID();
        UUID auditor = UUID.randomUUID();
        String action = uniqueAction("LINE_VOIDED");
        for (int i = 0; i < 3; i++) {
            audit.row(store, T0, action);
        }

        mvc.perform(post("/api/v1/backoffice/audit/exports").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .with(as(Actor.account(auditor, Set.of(Permission.AUDIT_VIEW.authority(store),
                                Permission.AUDIT_EXPORT.authority(store)))))
                        .content("""
                                {"storeId": "%s", "from": "%s", "to": "%s", "action": "%s"}""".formatted(store,
                                T0.minusSeconds(60), T0.plusSeconds(60), action)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json("""
                        {"status":422,"code":"EXPORT_TOO_LARGE","rows":3,"maxRows":2}"""));

        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'AUDIT_EXPORTED' AND actor_id = :a")
                .param("a", auditor).query(Long.class).single()).isZero();
    }
}
