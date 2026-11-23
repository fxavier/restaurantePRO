package com.xavier.backend.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.architecture.CompositeForeignKeyCoverageTest;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A80, A82 and A84 for production: tenants A and B use the same printer code and destination side by side. Through
 * the API a print job of B addressed by id from A is absent (404, never 403), a job of another store of A is
 * forbidden (403, D42) and the list of B's store from A is empty. With no {@code AccessPolicy} in front (the
 * application-role seam), B's rows are invisible to A, a row with B's {@code tenant_id} is refused ({@code 42501}), a
 * row never moves tenant ({@code RS002}), a child cannot reference B's parent (composite foreign key, proved as the
 * owner so that the row policies do not answer first), and an unbound read raises {@code RS003}. Strict: no
 * bootstrap fallback.
 */
@ProductionModuleTest
@StrictTenantScope
class ProductionTenantIsolationTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    static final UUID A_STORE = UUID.fromString("0190b400-0000-7000-8000-000000000011");
    static final UUID A_OTHER_STORE = UUID.fromString("0190b400-0000-7000-8000-000000000012");
    static final UUID B_STORE = UUID.fromString("0190b400-0000-7000-8000-000000000021");
    static final UUID A_PRINTER = UUID.fromString("0190b400-0000-7000-8000-0000000007a1");
    static final UUID A_OTHER_PRINTER = UUID.fromString("0190b400-0000-7000-8000-0000000007a2");
    static final UUID B_PRINTER = UUID.fromString("0190b400-0000-7000-8000-0000000007b1");
    static final UUID A_TICKET = UUID.fromString("0190b400-0000-7000-8000-0000000008a1");
    static final UUID A_OTHER_TICKET = UUID.fromString("0190b400-0000-7000-8000-0000000008a2");
    static final UUID B_TICKET = UUID.fromString("0190b400-0000-7000-8000-0000000008b1");
    static final UUID A_JOB = UUID.fromString("0190b400-0000-7000-8000-0000000009a1");
    static final UUID A_OTHER_JOB = UUID.fromString("0190b400-0000-7000-8000-0000000009a2");
    static final UUID B_JOB = UUID.fromString("0190b400-0000-7000-8000-0000000009b1");

    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void twoTenantsWithIdenticalCodes() {
        TenantTestSupport.ensureTenants();
        seed(A, A_STORE, A_PRINTER, A_TICKET, A_JOB);
        seed(B, B_STORE, B_PRINTER, B_TICKET, B_JOB);
        seed(A, A_OTHER_STORE, A_OTHER_PRINTER, A_OTHER_TICKET, A_OTHER_JOB);
        alerting(B, B_STORE, B_PRINTER);
    }

    /** A failed job and a late round of {@code store}: one alert of each kind on its POS. */
    private static void alerting(UUID tenant, UUID store, UUID printer) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            UUID ticket = UUID.randomUUID();
            app.jdbc().sql("""
                    INSERT INTO production.production_ticket (id, store_id, source_type, source_id, destination,
                        kind, printer_id, suppressed, content, created_at, routing, content_format)
                    VALUES (?, ?, 'BATCH', gen_random_uuid(), 'BAR', 'ORIGINAL', ?, false, 'late', now(), 'ROUTED',
                        'PLAIN')""").param(ticket).param(store).param(printer).update();
            app.jdbc().sql("""
                    INSERT INTO production.print_job (id, store_id, ticket_id, printer_id, kind, status, attempts,
                        next_attempt_at, started_at, dedupe_key, created_at, version)
                    VALUES (gen_random_uuid(), ?, ?, ?, 'ORIGINAL', 'FAILED', 1, now(), now(), ?, now(), 0)""")
                    .param(store).param(ticket).param(printer).param("failed:" + ticket).update();
            app.jdbc().sql("""
                    INSERT INTO production.kitchen_progress (ticket_id, store_id, destination, status, version,
                        queued_at)
                    VALUES (?, ?, 'BAR', 'QUEUED', 0, now() - interval '1 hour')""").param(ticket).param(store)
                    .update();
        }
    }

    /** As the application role in {@code tenant}: no tenant_id is written, the column default takes the bound one. */
    private static void seed(UUID tenant, UUID store, UUID printer, UUID ticket, UUID job) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("""
                    INSERT INTO organization.store (id, code, name, timezone, active, version, created_at, updated_at)
                    VALUES (?, ?, 'Baixa', 'Africa/Maputo', true, 0, now(), now())""").param(store)
                    .param("PN-" + store.toString().substring(31)).update();
            app.jdbc().sql("""
                    INSERT INTO production.printer (id, store_id, code, name, host, port, code_page, active, mode,
                        version, created_at, updated_at)
                    VALUES (?, ?, 'BAR', 'Bar', '127.0.0.1', 9100, 'CP860', true, 'HOLD', 0, now(), now())""")
                    .param(printer).param(store).update();
            app.jdbc().sql("INSERT INTO production.destination_route (store_id, destination_code, printer_id) "
                    + "VALUES (?, 'BAR', ?)").param(store).param(printer).update();
            app.jdbc().sql("""
                    INSERT INTO production.production_ticket (id, store_id, source_type, source_id, destination,
                        kind, printer_id, suppressed, content, created_at, routing, content_format)
                    VALUES (?, ?, 'BATCH', gen_random_uuid(), 'BAR', 'ORIGINAL', ?, false, 'iso', now(), 'ROUTED',
                        'PLAIN')""").param(ticket).param(store).param(printer).update();
            app.jdbc().sql("""
                    INSERT INTO production.print_job (id, store_id, ticket_id, printer_id, kind, status, attempts,
                        next_attempt_at, started_at, sent_at, dedupe_key, created_at, version)
                    VALUES (?, ?, ?, ?, 'ORIGINAL', 'SENT', 1, now(), now(), now(), ?, now(), 0)""")
                    .param(job).param(store).param(ticket).param(printer).param("iso:" + job).update();
        }
    }

    @Test
    void bothTenantsUseTheSamePrinterCodeAndDestination() {
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT p.tenant_id FROM production.printer p "
                        + "JOIN production.destination_route r ON r.printer_id = p.id AND r.destination_code = 'BAR' "
                        + "WHERE p.code = 'BAR' AND p.store_id IN (?, ?)").param(A_STORE).param(B_STORE)
                .query(UUID.class).list()).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void aTenantSeesOnlyItsOwnRows() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThat(app.jdbc().sql("SELECT id FROM production.printer WHERE id IN (?, ?, ?)").param(A_PRINTER)
                    .param(A_OTHER_PRINTER).param(B_PRINTER).query(UUID.class).list())
                    .containsExactlyInAnyOrder(A_PRINTER, A_OTHER_PRINTER);
            assertThat(app.jdbc().sql("SELECT id FROM production.print_job WHERE id IN (?, ?)").param(A_JOB)
                    .param(B_JOB).query(UUID.class).list()).containsExactly(A_JOB);
            assertThat(app.jdbc().sql("SELECT count(*) FROM production.destination_route WHERE store_id = ?")
                    .param(B_STORE).query(Long.class).single()).isZero();
        }
    }

    @Test
    void anotherTenantsJobById404AndAnotherStoresJob403() throws Exception {
        mvc.perform(post("/api/v1/backoffice/print-jobs/" + B_JOB + "/acknowledge").with(operator(A, null))
                .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/backoffice/print-jobs/" + A_OTHER_JOB + "/acknowledge")
                .with(operator(A, A_STORE)).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void theListOfAnotherTenantsStoreIsEmptyAndOwnIsNot() throws Exception {
        mvc.perform(get("/api/v1/backoffice/print-jobs").param("storeId", B_STORE.toString())
                        .with(operator(A, null))).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/api/v1/backoffice/print-jobs").param("storeId", A_STORE.toString())
                        .with(operator(A, null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(A_JOB.toString()));
    }

    /** The POS alerts count the bound tenant's store only; another tenant's store is no store of this one (403). */
    @Test
    void thePosAlertsCountOnlyTheTenantsOwnStore() throws Exception {
        mvc.perform(get("/api/v1/pos/production/alerts").with(viewer(B, B_STORE))).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingPrintJobs").value(1))
                .andExpect(jsonPath("$.delayedKitchenRounds").value(1));
        mvc.perform(get("/api/v1/pos/production/alerts").with(viewer(A, A_STORE))).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingPrintJobs").value(0))
                .andExpect(jsonPath("$.delayedKitchenRounds").value(0));
        mvc.perform(get("/api/v1/pos/production/alerts").with(viewer(A, B_STORE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRowWithAnotherTenantsIdIsRefused() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("""
                    INSERT INTO production.printer (id, tenant_id, store_id, code, name, host, port, code_page,
                        active, mode, version, created_at, updated_at)
                    VALUES (?, ?, ?, 'BAR-X', 'X', '127.0.0.1', 9100, 'CP860', true, 'HOLD', 0, now(), now())""")
                    .param(UUID.randomUUID()).param(B).param(A_STORE).update(),
                    StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        }
    }

    @Test
    void aRowNeverMovesToAnotherTenant() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE production.printer SET tenant_id = ? "
                    + "WHERE id = ?").param(B).param(A_PRINTER).update(), StoreScopeViolations.Kind.STORE_CHANGE);
        }
    }

    @Test
    void aChildCannotReferenceAnotherTenantsParent() {
        assertThatThrownBy(() -> OwnerDatabase.shared().jdbc().sql("""
                INSERT INTO production.print_job (id, tenant_id, store_id, ticket_id, printer_id, kind, status,
                    attempts, next_attempt_at, started_at, sent_at, dedupe_key, created_at, version)
                VALUES (?, ?, ?, ?, ?, 'ORIGINAL', 'SENT', 1, now(), now(), now(), ?, now(), 0)""")
                .param(UUID.randomUUID()).param(A).param(B_STORE).param(A_TICKET).param(B_PRINTER)
                .param("forged:" + UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
    }

    @Test
    void anUnboundReadFailsClosed() {
        try (AppRoleSession app = AppRoleSession.open().noTenant().allStores()) {
            for (String table : List.of("printer", "destination_route", "terminal_printer", "production_ticket",
                    "print_job", "kitchen_progress")) {
                AppRoleSession.assertRefused(() -> app.jdbc().sql("SELECT count(*) FROM production." + table)
                        .query(Long.class).single(), StoreScopeViolations.Kind.TENANT_MISSING);
            }
        }
    }

    @Test
    void theModuleIsTenantProtected() {
        RowLevelSecurityCoverageTest.assertModuleTenantProtected("production");
        CompositeForeignKeyCoverageTest.assertModuleKeysIncludeTheTenant("production");
    }

    /** A POS session of {@code tenant} in {@code store} with PRODUCTION_VIEW (its membership as the details). */
    private static RequestPostProcessor viewer(UUID tenant, UUID store) {
        Set<String> authorities = Set.of(Permission.PRODUCTION_VIEW.authority(store));
        Actor actor = Actor.employee(UUID.randomUUID(), UUID.randomUUID(), store, authorities);
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        token.setDetails(new TenantContext(tenant, MembershipKind.TERMINAL, actor.userId(), "test:isolation"));
        return authentication(token);
    }

    /** A BackOffice account of {@code tenant} operating and viewing production in {@code store}, or everywhere. */
    private static RequestPostProcessor operator(UUID tenant, UUID store) {
        Set<String> authorities = Set.of(
                store == null ? Permission.PRODUCTION_OPERATE.authority() : Permission.PRODUCTION_OPERATE.authority(store),
                store == null ? Permission.PRODUCTION_VIEW.authority() : Permission.PRODUCTION_VIEW.authority(store));
        Actor actor = Actor.account(UUID.randomUUID(), authorities);
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        token.setDetails(new TenantContext(tenant, MembershipKind.ACCOUNT, actor.userId(), "test:isolation"));
        return authentication(token);
    }
}
