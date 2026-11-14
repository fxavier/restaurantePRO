package com.xavier.backend.audit;

import static com.xavier.backend.audit.AuditFixtures.uniqueAction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StrictStoreScope;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.identity.IdentityFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Row-level security on the audit trail (D168 special, A71): reads are scoped, and a store-less row (system, company)
 * is read only by an all-stores scope, as D82 does; inserts are open, because a login, a logout or a lockout is
 * audited before an actor exists; there is no update or delete policy (D18 refuses them anyway). Strict: the test
 * thread has no fixture fallback. Rows are seeded by the owner.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@StrictStoreScope
class AuditRowLevelSecurityTest {

    static final String EVENTS = "/api/v1/backoffice/audit/events";
    static final Instant T0 = Instant.parse("2021-03-01T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AuditLog auditLog;

    @Autowired
    TransactionTemplate tx;

    final AuditFixtures owner = new AuditFixtures(OwnerDatabase.shared().jdbc());
    UUID storeA;
    UUID storeB;
    String action;
    UUID inA;
    UUID inB;
    UUID storeLess;

    @BeforeEach
    void aRowOfEachStoreAndOneOfNoStore() {
        storeA = UUID.randomUUID();
        storeB = UUID.randomUUID();
        action = uniqueAction("RLS");
        inA = owner.row(storeA, T0, action);
        inB = owner.row(storeB, T0, action);
        storeLess = owner.row(null, T0, action);
    }

    @AfterEach
    void noActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theAuditSchemaIsClassifiedAndItsPoliciesAreAReadAndAnOpenInsert() {
        RowLevelSecurityCoverageTest.assertModuleClassified("audit", "audit_event");

        assertThat(OwnerDatabase.shared().jdbc().sql("""
                        SELECT polcmd::text AS cmd, pg_catalog.pg_get_expr(polqual, polrelid) AS qual,
                               pg_catalog.pg_get_expr(polwithcheck, polrelid) AS with_check
                        FROM pg_catalog.pg_policy WHERE polrelid = 'audit.audit_event'::regclass ORDER BY polcmd""")
                .query().listOfRows()).as("SELECT and INSERT only, no UPDATE or DELETE policy").satisfiesExactly(
                insert -> assertThat(insert).containsEntry("cmd", "a").containsEntry("with_check", "true"),
                read -> assertThat(read).containsEntry("cmd", "r").containsEntry("qual", canonicalStoreScope()));
    }

    @Test
    void storeLessRowsAreReadOnlyByAnAllStoresScope() {
        actor(Permission.AUDIT_VIEW.authority(storeA));
        assertThat(rows()).containsExactly(inA);

        actor(Permission.AUDIT_VIEW.authority(storeA), Permission.AUDIT_VIEW.authority(storeB));
        assertThat(rows()).containsExactlyInAnyOrder(inA, inB);

        actor(Permission.AUDIT_VIEW.authority());
        assertThat(rows()).containsExactlyInAnyOrder(inA, inB, storeLess);
        assertThat(jdbc.sql("UPDATE audit.audit_event SET reason = 'x' WHERE id = ?").param(inA).update())
                .as("no update policy, even for all stores").isZero();
        assertThat(jdbc.sql("DELETE FROM audit.audit_event WHERE id = ?").param(inA).update())
                .as("no delete policy").isZero();
    }

    @Test
    void withNoActorAndNoScopeAReadFailsClosedButAnAuditRowIsWritten() {
        assertThatThrownBy(this::rows).rootCause().hasMessageContaining("store scope not set");

        tx.executeWithoutResult(status -> auditLog.recordAsSystem(action, "Test", "no-actor", storeA, null, null,
                Map.of()));

        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM audit.audit_event WHERE action = ? "
                + "AND entity_id = 'no-actor'").param(action).query(Long.class).single()).isEqualTo(1);
    }

    /** A login is audited before any actor exists: the insert policy needs no scope (D20, D41). */
    @Test
    void aLoginIsAuditedWithNoActor() throws Exception {
        IdentityFixtures identity = new IdentityFixtures(mvc);
        IdentityFixtures.User user = identity.newUser("initial password 1", Set.of(IdentityFixtures.MANAGER));

        mvc.perform(IdentityFixtures.loginRequest(user.username(), "initial password 1")).andExpect(status().isOk());

        assertThat(OwnerDatabase.shared().jdbc().sql("""
                        SELECT count(*) FROM audit.audit_event
                        WHERE action = 'USER_LOGGED_IN' AND entity_id = ?""").param(user.id().toString())
                .query(Long.class).single()).isEqualTo(1);
    }

    /** D42 (b) under row-level security: a row of another store or of no store is 403 to a store auditor. */
    @Test
    void oneRowOfAnotherStoreIsForbiddenNotMissing() throws Exception {
        RequestPostProcessor auditorOfA = AuditFixtures.account(Permission.AUDIT_VIEW.authority(storeA));

        mvc.perform(get(EVENTS + "/" + inA).with(auditorOfA)).andExpect(status().isOk());
        mvc.perform(get(EVENTS + "/" + inB).with(auditorOfA)).andExpect(status().isForbidden());
        mvc.perform(get(EVENTS + "/" + storeLess).with(auditorOfA)).andExpect(status().isForbidden());
        mvc.perform(get(EVENTS + "/" + UUID.randomUUID())
                        .with(AuditFixtures.account(Permission.AUDIT_VIEW.authority())))
                .andExpect(status().isNotFound());
    }

    /** A console function only (D168): the pool cannot call it. */
    @Test
    void theApplicationRoleCannotWriteConsoleEvents() {
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT has_function_privilege(?, "
                        + "'audit.record_console_event(text, text, text, text)', 'EXECUTE')")
                .param(TestcontainersConfiguration.APP_ROLE).query(Boolean.class).single()).isFalse();
    }

    /** The canonical VIS(store_id) as the V1008 helper writes it, deparsed (as RowLevelSecurityCoverageTest does). */
    private static String canonicalStoreScope() {
        return OwnerDatabase.shared().call(owner -> {
            owner.sql("CREATE TEMPORARY TABLE audit_reference (store_id uuid) ON COMMIT DROP").update();
            owner.sql("SELECT shared.rls_protect_store_table('pg_temp.audit_reference')").query().listOfRows();
            String qual = owner.sql("SELECT pg_catalog.pg_get_expr(polqual, polrelid) FROM pg_catalog.pg_policy "
                    + "WHERE polrelid = 'pg_temp.audit_reference'::regclass").query(String.class).single();
            owner.sql("DELETE FROM shared.rls_protected_table WHERE table_name = 'pg_temp.audit_reference'::regclass")
                    .update();
            return qual;
        });
    }

    private List<UUID> rows() {
        return jdbc.sql("SELECT id FROM audit.audit_event WHERE action = ?").param(action).query(UUID.class).list();
    }

    private static void actor(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                Actor.account(UUID.randomUUID(), Set.of(authorities)), null, List.of()));
    }

}
