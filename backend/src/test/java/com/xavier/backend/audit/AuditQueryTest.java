package com.xavier.backend.audit;

import static com.xavier.backend.audit.AuditFixtures.account;
import static com.xavier.backend.audit.AuditFixtures.uniqueAction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.time.Duration;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * D82 query side: who sees which rows (AccessPolicy.scopeOf), the bounded range, the filters and the paging. Rows are
 * written at fixed past instants, so nothing depends on the wall clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditQueryTest {

    static final String EVENTS = "/api/v1/backoffice/audit/events";
    static final Instant T0 = Instant.parse("2021-03-01T10:00:00Z");
    static final String HISTORY = "/api/v1/backoffice/audit/entities/Order/";
    static final String VIEW = Permission.AUDIT_VIEW.authority();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AuditLog auditLog;

    @Autowired
    TenantScopes tenants;

    @Autowired
    PlatformTransactionManager transactions;

    AuditFixtures audit;
    final UUID storeA = UUID.randomUUID();
    final UUID storeB = UUID.randomUUID();
    final String action = uniqueAction("LINE_VOIDED");

    @BeforeEach
    void fixtures() {
        audit = new AuditFixtures(jdbc);
    }

    @Test
    void aStoreScopedAuditorSeesOnlyTheRowsOfTheirStore() throws Exception {
        UUID inA = audit.row(storeA, T0, action);
        audit.row(storeB, T0, action);
        audit.row(null, T0, action);

        mvc.perform(list().with(account(Permission.AUDIT_VIEW.authority(storeA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(inA.toString()))
                .andExpect(jsonPath("$.content[0].storeId").value(storeA.toString()));
    }

    /** D183: a support action is audited as SUPPORT, stored and filterable like the other kinds. */
    @Test
    void anEntryOfActorKindSupportIsStoredAndListed() throws Exception {
        UUID support = audit.row(storeA, T0, action, "Order", UUID.randomUUID().toString(), UUID.randomUUID(),
                "SUPPORT", null, "{}");
        audit.row(storeA, T0, action);

        mvc.perform(list().param("actorKind", "SUPPORT").with(account(VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(support.toString()))
                .andExpect(jsonPath("$.content[0].actorKind").value("SUPPORT"));
    }

    /**
     * D183: who acts in a SUPPORT membership is written as SUPPORT, though its actor is the tenant's support account
     * (an ACCOUNT): the kind comes from the bound membership, for the use case's write and for {@code recordAs}.
     */
    @Test
    void anActionInASupportMembershipIsWrittenAsSupport() {
        UUID supportAccount = UUID.randomUUID();
        Actor actor = Actor.account(supportAccount, Set.of());
        TenantContext support = new TenantContext(TenantTestSupport.BOOTSTRAP, MembershipKind.SUPPORT,
                UUID.randomUUID(), "test:support");
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(actor, null, List.of()));
        try {
            tenants.callIn(support, () -> new TransactionTemplate(transactions).execute(status -> {
                auditLog.recordAsCurrentActor(action, "Order", "o-1", storeA, null, null, Map.of());
                auditLog.recordAs(actor, action, "Order", "o-2", storeA, null, Map.of());
                return null;
            }));
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(jdbc.sql("SELECT entity_id || ' ' || actor_kind FROM audit.audit_event WHERE action = ? "
                        + "AND actor_id = ? ORDER BY entity_id").param(action).param(supportAccount)
                .query(String.class).list()).containsExactly("o-1 SUPPORT", "o-2 SUPPORT");
    }

    @Test
    void aStoreScopedAuditorNamingAnotherStoreIsForbidden() throws Exception {
        audit.row(storeB, T0, action);

        mvc.perform(list().param("storeId", storeB.toString())
                        .with(account(Permission.AUDIT_VIEW.authority(storeA))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAuditorOfTwoStoresSeesBoth() throws Exception {
        UUID inA = audit.row(storeA, T0, action);
        UUID inB = audit.row(storeB, T0, action);
        audit.row(UUID.randomUUID(), T0, action);

        mvc.perform(list().with(account(Permission.AUDIT_VIEW.authority(storeA),
                        Permission.AUDIT_VIEW.authority(storeB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder(inA.toString(), inB.toString())));
    }

    @Test
    void anInstallationWideAuditorSeesEveryStoreAndTheStoreLessRows() throws Exception {
        UUID inA = audit.row(storeA, T0, action);
        UUID inB = audit.row(storeB, T0, action);
        UUID system = audit.row(null, T0, action);

        mvc.perform(list().with(account(VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id")
                        .value(containsInAnyOrder(inA.toString(), inB.toString(), system.toString())));
        mvc.perform(list().param("storeId", storeA.toString()).with(account(VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder(inA.toString())));
    }

    @Test
    void aWaiterIsForbidden() throws Exception {
        mvc.perform(list().with(account(Permission.ORDER_EDIT.authority(storeA),
                        Permission.DINING_VIEW.authority(storeA))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRangeOver92DaysIsRefusedWithACode() throws Exception {
        mvc.perform(get(EVENTS).param("from", T0.toString()).param("to", T0.plus(Duration.ofDays(92)).toString())
                        .with(account(VIEW)))
                .andExpect(status().isOk());
        mvc.perform(get(EVENTS).param("from", T0.toString())
                        .param("to", T0.plus(Duration.ofDays(92)).plusSeconds(1).toString())
                        .with(account(VIEW)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json("""
                        {"status":422,"code":"RANGE_TOO_LARGE","maxDays":92,
                         "detail":"The range is longer than 92 days: narrow it."}"""));
    }

    @Test
    void aMissingOrInvertedRangeOrAnUnknownActorKindIsABadRequest() throws Exception {
        mvc.perform(get(EVENTS).param("from", T0.toString()).with(account(VIEW))).andExpect(status().isBadRequest());
        mvc.perform(get(EVENTS).param("to", T0.toString()).with(account(VIEW))).andExpect(status().isBadRequest());
        mvc.perform(get(EVENTS).param("from", T0.toString()).param("to", T0.toString()).with(account(VIEW)))
                .andExpect(status().isBadRequest());
        mvc.perform(list().param("actorKind", "ROBOT").with(account(VIEW))).andExpect(status().isBadRequest());
    }

    @Test
    void theRangeIncludesFromAndExcludesTo() throws Exception {
        UUID atFrom = audit.row(storeA, T0, action);
        audit.row(storeA, T0.plusSeconds(60), action);

        mvc.perform(get(EVENTS).param("from", T0.toString()).param("to", T0.plusSeconds(60).toString())
                        .param("action", action).with(account(VIEW)))
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder(atFrom.toString())));
    }

    @Test
    void everyGivenFilterNarrowsTheRows() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID match = audit.row(storeA, T0, action, "Order", "o-1", actor, "EMPLOYEE", null, "{}");
        audit.row(storeA, T0, action, "Order", "o-1", UUID.randomUUID(), "EMPLOYEE", null, "{}");
        audit.row(storeA, T0, action, "Order", "o-2", actor, "EMPLOYEE", null, "{}");
        audit.row(storeA, T0, action, "Table", "o-1", actor, "EMPLOYEE", null, "{}");
        audit.row(storeA, T0, action, "Order", "o-1", actor, "ACCOUNT", null, "{}");
        audit.row(storeA, T0, uniqueAction("OTHER"), "Order", "o-1", actor, "EMPLOYEE", null, "{}");

        mvc.perform(list().param("storeId", storeA.toString()).param("actorId", actor.toString())
                        .param("actorKind", "EMPLOYEE").param("entityType", "Order").param("entityId", "o-1")
                        .with(account(VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder(match.toString())));
    }

    @Test
    void pagesAreNewestFirstAndTiesAreBrokenByIdDescending() throws Exception {
        UUID oldest = audit.row(storeA, T0.minusSeconds(10), action);
        UUID tieOne = audit.row(storeA, T0, action);
        UUID tieTwo = audit.row(storeA, T0, action);
        UUID newest = audit.row(storeA, T0.plusSeconds(10), action);
        // PostgreSQL orders uuids by unsigned bytes, as their hex text sorts; UUID.compareTo is signed
        String[] tie = tieOne.toString().compareTo(tieTwo.toString()) > 0
                ? new String[] {tieOne.toString(), tieTwo.toString()}
                : new String[] {tieTwo.toString(), tieOne.toString()};

        mvc.perform(list().param("size", "2").param("sort", "occurredAt,asc").with(account(VIEW)))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content[0].id").value(newest.toString()))
                .andExpect(jsonPath("$.content[1].id").value(tie[0]));
        mvc.perform(list().param("size", "2").param("page", "1").with(account(VIEW)))
                .andExpect(jsonPath("$.content[0].id").value(tie[1]))
                .andExpect(jsonPath("$.content[1].id").value(oldest.toString()));
    }

    @Test
    void oneRowIsReadWithItsDetailsAsAnObject() throws Exception {
        UUID id = audit.row(storeA, T0, action, "Order", "o-1", UUID.randomUUID(), "EMPLOYEE", "wrong dish",
                "{\"lineId\": \"l-1\", \"qty\": 2}");

        mvc.perform(get(EVENTS + "/" + id).with(account(Permission.AUDIT_VIEW.authority(storeA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value(action))
                .andExpect(jsonPath("$.reason").value("wrong dish"))
                .andExpect(jsonPath("$.details.lineId").value("l-1"))
                .andExpect(jsonPath("$.details.qty").value(2));
    }

    @Test
    void oneRowOfAnotherStoreOrOfNoStoreIsForbiddenToAStoreScopedAuditor() throws Exception {
        UUID inB = audit.row(storeB, T0, action);
        UUID system = audit.row(null, T0, action);

        mvc.perform(get(EVENTS + "/" + inB).with(account(Permission.AUDIT_VIEW.authority(storeA))))
                .andExpect(status().isForbidden());
        mvc.perform(get(EVENTS + "/" + system).with(account(Permission.AUDIT_VIEW.authority(storeA))))
                .andExpect(status().isForbidden());
        mvc.perform(get(EVENTS + "/" + system).with(account(VIEW))).andExpect(status().isOk());
        mvc.perform(get(EVENTS + "/" + UUID.randomUUID()).with(account(VIEW))).andExpect(status().isNotFound());
    }

    @Test
    void anEntityHistoryShowsAStoreScopedAuditorOnlyTheRowsOfTheirStore() throws Exception {
        String order = UUID.randomUUID().toString();
        UUID inA = audit.row(storeA, T0, action, "Order", order, UUID.randomUUID(), "EMPLOYEE", null, "{}");
        UUID inB = audit.row(storeB, T0.plusSeconds(1), action, "Order", order, UUID.randomUUID(), "EMPLOYEE",
                null, "{}");
        UUID system = audit.row(null, T0.plusSeconds(2), action, "Order", order, null, "SYSTEM", null, "{}");
        audit.row(storeA, T0, action, "Table", order, UUID.randomUUID(), "EMPLOYEE", null, "{}");

        mvc.perform(get(HISTORY + order).with(account(Permission.AUDIT_VIEW.authority(storeA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(inA.toString()));
        mvc.perform(get(HISTORY + order).with(account(VIEW)))
                .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.contains(system.toString(),
                        inB.toString(), inA.toString())));
    }

    @Test
    void aWaiterCannotReadOneRowNorAHistory() throws Exception {
        UUID inA = audit.row(storeA, T0, action);
        var waiter = account(Permission.ORDER_EDIT.authority(storeA), Permission.DINING_VIEW.authority(storeA));

        mvc.perform(get(EVENTS + "/" + inA).with(waiter)).andExpect(status().isForbidden());
        mvc.perform(get(HISTORY + "x").with(waiter)).andExpect(status().isForbidden());
    }

    @Test
    void theStoreActionTimeIndexExists() {
        String definition = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'audit' "
                        + "AND indexname = 'audit_event_store_action_ix'")
                .query(String.class).single();

        assertThat(definition).endsWith("(tenant_id, store_id, action, occurred_at)");
    }

    @Test
    void theTimeIdIndexExists() {
        String definition = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'audit' "
                        + "AND indexname = 'audit_event_occurred_ix'")
                .query(String.class).single();

        assertThat(definition).endsWith("(tenant_id, occurred_at, id)");
    }

    MockHttpServletRequestBuilder list() {
        return get(EVENTS).param("from", T0.minusSeconds(3600).toString()).param("to", T0.plusSeconds(3600).toString())
                .param("action", action);
    }
}
