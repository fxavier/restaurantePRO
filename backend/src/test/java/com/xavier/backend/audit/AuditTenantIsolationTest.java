package com.xavier.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A80, A82 and A84 (audit): tenants A and B each audit the same action on the same entity id. Through the API an
 * auditor of A sees only A's events, B's event addressed by id is 404 (as an id that never existed), and the history of
 * the shared entity id lists A's row only. With no {@code AccessPolicy} in front (the application-role seam), B's rows
 * are invisible to A, a row with B's {@code tenant_id} is refused ({@code 42501}), a row never moves tenant
 * ({@code RS002}) and an unbound read raises {@code RS003}. An insert with no tenant bound fails too: an audit row
 * always belongs to a tenant. Strict: no bootstrap-tenant fallback.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@StrictTenantScope
class AuditTenantIsolationTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    static final UUID A_EVENT = UUID.fromString("0190b800-0000-7000-8000-0000000001a1");
    static final UUID B_EVENT = UUID.fromString("0190b800-0000-7000-8000-0000000001b1");
    static final String ENTITY = "ISO-ENTITY-" + UUID.randomUUID();

    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void twoTenantsAuditTheSameEntity() {
        TenantTestSupport.ensureTenants();
        event(A, A_EVENT);
        event(B, B_EVENT);
    }

    private static void event(UUID tenant, UUID id) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("INSERT INTO audit.audit_event (id, occurred_at, actor_kind, action, entity_type, "
                    + "entity_id) VALUES (?, '2026-01-05T10:00:00Z', 'SYSTEM', 'ISO_ACTION', 'IsoEntity', ?)")
                    .param(id).param(ENTITY).update();
        }
    }

    @Test
    void aTenantSeesOnlyItsOwnEvents() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThat(app.jdbc().sql("SELECT id FROM audit.audit_event WHERE entity_id = ?").param(ENTITY)
                    .query(UUID.class).list()).containsExactly(A_EVENT);
        }
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT tenant_id FROM audit.audit_event WHERE entity_id = ?")
                .param(ENTITY).query(UUID.class).list()).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void anotherTenantsEventById404AndOwnEvent200() throws Exception {
        mvc.perform(get("/api/v1/backoffice/audit/events/" + B_EVENT).with(in(A, Permission.AUDIT_VIEW)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/backoffice/audit/events/" + A_EVENT).with(in(A, Permission.AUDIT_VIEW)))
                .andExpect(status().isOk());
    }

    @Test
    void theHistoryOfTheSharedEntityListsOnlyTheTenantsEvents() throws Exception {
        mvc.perform(get("/api/v1/backoffice/audit/entities/IsoEntity/" + ENTITY).with(in(A, Permission.AUDIT_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(A_EVENT.toString()));
        mvc.perform(get("/api/v1/backoffice/audit/events").param("action", "ISO_ACTION")
                        .param("from", "2026-01-01T00:00:00Z").param("to", "2026-02-01T00:00:00Z")
                        .with(in(A, Permission.AUDIT_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void aRowWithAnotherTenantsIdIsRefused() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("INSERT INTO audit.audit_event (id, tenant_id, "
                    + "occurred_at, actor_kind, action, entity_type, entity_id) VALUES (?, ?, now(), 'SYSTEM', "
                    + "'ISO_ACTION', 'IsoEntity', 'x')").param(UUID.randomUUID()).param(B).update(),
                    StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        }
    }

    @Test
    void aRowNeverMovesToAnotherTenant() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE audit.audit_event SET tenant_id = ? "
                    + "WHERE id = ?").param(B).param(A_EVENT).update(), StoreScopeViolations.Kind.STORE_CHANGE);
        }
    }

    @Test
    void anUnboundReadAndAnUnboundInsertFailClosed() {
        try (AppRoleSession app = AppRoleSession.open().noTenant().allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("SELECT count(*) FROM audit.audit_event")
                    .query(Long.class).single(), StoreScopeViolations.Kind.TENANT_MISSING);
            AppRoleSession.assertRefused(() -> app.jdbc().sql("INSERT INTO audit.audit_event (id, occurred_at, "
                    + "actor_kind, action, entity_type, entity_id) VALUES (?, now(), 'SYSTEM', 'ISO_ACTION', "
                    + "'IsoEntity', 'x')").param(UUID.randomUUID()).update(),
                    StoreScopeViolations.Kind.TENANT_MISSING);
        }
    }

    @Test
    void theModuleIsTenantProtected() {
        RowLevelSecurityCoverageTest.assertModuleTenantProtected("audit");
    }

    /** An installation-wide auditor of {@code tenant}. */
    private static RequestPostProcessor in(UUID tenant, Permission permission) {
        String authority = permission.authority();
        Actor actor = Actor.account(UUID.randomUUID(), Set.of(authority));
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                List.of(new SimpleGrantedAuthority(authority)));
        token.setDetails(new TenantContext(tenant, MembershipKind.ACCOUNT, actor.userId(), "test:isolation"));
        return authentication(token);
    }
}
