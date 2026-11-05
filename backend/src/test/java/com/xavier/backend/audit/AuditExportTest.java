package com.xavier.backend.audit;

import static com.xavier.backend.audit.AuditFixtures.as;
import static com.xavier.backend.audit.AuditFixtures.uniqueAction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * D82 export: CSV of the visible rows only, AUDIT_EXPORT on top of the AUDIT_VIEW scope, at most 100 000 rows, and
 * every export recorded as AUDIT_EXPORTED with its filters (spec §10 "autorização adicional para… exportações").
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditExportTest {

    static final String EXPORTS = "/api/v1/backoffice/audit/exports";
    static final String HEADER = "id,occurredAt,actorKind,actorId,approverId,action,entityType,entityId,storeId,"
            + "terminalId,reason,details";
    static final Instant T0 = Instant.parse("2022-06-01T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    AuditFixtures audit;
    final UUID storeA = UUID.randomUUID();
    final UUID storeB = UUID.randomUUID();
    final UUID auditorId = UUID.randomUUID();
    final String action = uniqueAction("LINE_VOIDED");

    @BeforeEach
    void fixtures() {
        audit = new AuditFixtures(jdbc);
    }

    @Test
    void aStoreScopedAuditorExportsOnlyTheRowsOfTheirStoreAsCsv() throws Exception {
        UUID inA = audit.row(storeA, T0, action);
        UUID inB = audit.row(storeB, T0, action);
        UUID system = audit.row(null, T0, action);

        String csv = export(auditor(Permission.AUDIT_VIEW.authority(storeA), Permission.AUDIT_EXPORT.authority(storeA)),
                filters(null))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"audit-events.csv\""))
                .andReturn().getResponse().getContentAsString();

        List<String> lines = csv.lines().toList();
        assertThat(lines).first().isEqualTo(HEADER);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).startsWith(inA + ",2022-06-01T10:00:00Z,EMPLOYEE,");
        assertThat(csv).doesNotContain(inB.toString()).doesNotContain(system.toString());
    }

    @Test
    void anExportIsRecordedAsAuditExportedWithItsFilters() throws Exception {
        audit.row(storeA, T0, action);

        export(auditor(Permission.AUDIT_VIEW.authority(storeA), Permission.AUDIT_EXPORT.authority(storeA)),
                filters(storeA)).andExpect(status().isOk());

        Map<String, Object> row = jdbc.sql("""
                        SELECT actor_id, actor_kind, store_id, entity_type, details->>'action' AS action,
                               details->>'storeId' AS store, details->>'from' AS "from", details->>'to' AS "to",
                               (details->>'rows')::int AS rows
                        FROM audit.audit_event WHERE action = 'AUDIT_EXPORTED' AND actor_id = :actor""")
                .param("actor", auditorId).query().singleRow();
        assertThat(row).containsEntry("actor_kind", "ACCOUNT")
                .containsEntry("store_id", storeA)
                .containsEntry("entity_type", "AuditExport")
                .containsEntry("action", action)
                .containsEntry("store", storeA.toString())
                .containsEntry("from", T0.minusSeconds(3600).toString())
                .containsEntry("to", T0.plusSeconds(3600).toString())
                .containsEntry("rows", 1);
    }

    @Test
    void exportingNeedsAuditExportAndRecordsNothingWhenRefused() throws Exception {
        audit.row(storeA, T0, action);

        export(auditor(Permission.AUDIT_VIEW.authority(storeA)), filters(null)).andExpect(status().isForbidden());
        export(auditor(Permission.AUDIT_EXPORT.authority(storeA)), filters(null)).andExpect(status().isForbidden());
        export(auditor(Permission.ORDER_EDIT.authority(storeA)), filters(null)).andExpect(status().isForbidden());

        assertThat(exportsRecorded()).isZero();
    }

    @Test
    void aStoreScopedAuditorCannotExportAnotherStore() throws Exception {
        audit.row(storeB, T0, action);

        export(auditor(Permission.AUDIT_VIEW.authority(storeA), Permission.AUDIT_EXPORT.authority(storeA)),
                filters(storeB)).andExpect(status().isForbidden());

        assertThat(exportsRecorded()).isZero();
    }

    @Test
    void theExportCoversOnlyStoresWhereBothPermissionsAreHeld() throws Exception {
        UUID inA = audit.row(storeA, T0, action);
        UUID inB = audit.row(storeB, T0, action);

        String csv = export(auditor(Permission.AUDIT_VIEW.authority(), Permission.AUDIT_EXPORT.authority(storeA)),
                filters(null)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(csv).contains(inA.toString()).doesNotContain(inB.toString());
        export(auditor(Permission.AUDIT_VIEW.authority(storeA), Permission.AUDIT_EXPORT.authority(storeB)),
                filters(null)).andExpect(status().isForbidden());
    }

    @Test
    void fieldsAreQuotedAndAFormulaIsDefused() throws Exception {
        audit.row(storeA, T0, action, "Order", "o-1", UUID.randomUUID(), "EMPLOYEE",
                "=HYPERLINK(\"x\"), then\na new line", "{\"lineId\": \"l-1\"}");

        String csv = export(auditor(Permission.AUDIT_VIEW.authority(), Permission.AUDIT_EXPORT.authority()),
                filters(storeA)).andReturn().getResponse().getContentAsString();

        assertThat(csv).endsWith(",\"'=HYPERLINK(\"\"x\"\"), then\na new line\",\"{\"\"lineId\"\": \"\"l-1\"\"}\"\r\n");
    }

    @Test
    void anExportOverOneHundredThousandRowsIsRefusedAndNotRecorded() throws Exception {
        jdbc.sql("""
                        INSERT INTO audit.audit_event (id, occurred_at, actor_kind, action, entity_type, entity_id,
                                                       store_id)
                        SELECT gen_random_uuid(), :at, 'SYSTEM', :action, 'Order', n::text, :store
                        FROM generate_series(1, 100001) AS n""")
                .param("at", Timestamp.from(T0)).param("action", action).param("store", storeA).update();

        export(auditor(Permission.AUDIT_VIEW.authority(), Permission.AUDIT_EXPORT.authority()), filters(storeA))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json("""
                        {"status":422,"code":"EXPORT_TOO_LARGE","rows":100001,"maxRows":100000}"""));

        assertThat(exportsRecorded()).isZero();
    }

    @Test
    void anExportNeedsABoundedRange() throws Exception {
        var auditor = auditor(Permission.AUDIT_VIEW.authority(), Permission.AUDIT_EXPORT.authority());

        export(auditor, """
                {"to": "%s"}""".formatted(T0)).andExpect(status().isBadRequest());
        export(auditor, """
                {"from": "2022-01-01T00:00:00Z", "to": "2022-06-01T00:00:00Z"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json("""
                        {"code":"RANGE_TOO_LARGE"}"""));
        assertThat(exportsRecorded()).isZero();
    }

    private String filters(UUID storeId) {
        return """
                {"storeId": %s, "from": "%s", "to": "%s", "action": "%s"}""".formatted(
                storeId == null ? "null" : "\"" + storeId + "\"", T0.minusSeconds(3600), T0.plusSeconds(3600),
                action);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor auditor(String... authorities) {
        return as(Actor.account(auditorId, Set.of(authorities)));
    }

    private ResultActions export(org.springframework.test.web.servlet.request.RequestPostProcessor actor,
                                 String body) throws Exception {
        return mvc.perform(post(EXPORTS).with(actor).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long exportsRecorded() {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'AUDIT_EXPORTED' AND actor_id = :a")
                .param("a", auditorId).query(Long.class).single();
    }
}
