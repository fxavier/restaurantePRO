package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A80, A82 and A84 (reporting): tenants A and B, each with its own company, store and projections, and a rebuild
 * RUNNING for the same store id. A report of A's store sums A's rows only, even though B holds a document with A's
 * store id (the tenant policy, not the store filter, hides it); a report of B's store from A is 404. With no
 * {@code AccessPolicy} in front (the application-role seam), B's rows are invisible to A, a row with B's
 * {@code tenant_id} is refused ({@code 42501}), a row never moves tenant ({@code RS002}), a child cannot reference B's
 * parent (composite foreign key), and an unbound read raises {@code RS003}. Strict: no bootstrap-tenant fallback.
 */
@ReportingModuleTest
@StrictTenantScope
class ReportingTenantIsolationTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    static final UUID A_STORE = UUID.fromString("0190b700-0000-7000-8000-0000000000a1");
    static final UUID B_STORE = UUID.fromString("0190b700-0000-7000-8000-0000000000b1");
    static final UUID A_DOCUMENT = UUID.fromString("0190b700-0000-7000-8000-0000000001a1");
    static final UUID B_DOCUMENT = UUID.fromString("0190b700-0000-7000-8000-0000000001b1");
    static final UUID B_LEAK = UUID.fromString("0190b700-0000-7000-8000-0000000001b2");
    static final UUID SHARED_STORE = UUID.fromString("0190b700-0000-7000-8000-0000000000c1");

    static final String DAY = "2026-01-05";

    static final List<String> TABLES = List.of("sales_record", "sales_record_line", "sales_record_tax",
            "payment_record", "credit_record", "void_record", "discount_record", "offer_cost_record",
            "consumption_record", "consumption_waste_record", "cost_variance_record", "rebuild_run");

    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void twoTenantsWithTheirOwnProjections() {
        TenantTestSupport.ensureTenants();
        seed(A, "Company A", A_STORE, A_DOCUMENT, A_STORE, "10.00");
        seed(B, "Company B", B_STORE, B_DOCUMENT, B_STORE, "20.00");
        // B also holds a document that carries A's store id: only the tenant policy keeps it out of A's report
        document(B, B_LEAK, A_STORE, "999.00");
    }

    private static void seed(UUID tenant, String company, UUID store, UUID document, UUID documentStore,
                             String gross) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("INSERT INTO organization.company_profile (id, name, tax_id, country, currency, version, "
                    + "created_at, updated_at) VALUES (?, ?, 'TAX-ISO', 'MZ', 'MZN', 0, now(), now()) "
                    + "ON CONFLICT (tenant_id) DO NOTHING").param(UUID.randomUUID()).param(company).update();
            app.jdbc().sql("INSERT INTO organization.store (id, code, name, timezone, active, version, created_at, "
                    + "updated_at) VALUES (?, 'RPT-1', 'Baixa', 'Africa/Maputo', true, 0, now(), now())")
                    .param(store).update();
            app.jdbc().sql("INSERT INTO reporting.rebuild_run (id, store_id, from_date, to_date, status, projected, "
                    + "started_at) VALUES (?, ?, DATE '" + DAY + "', DATE '" + DAY + "', 'RUNNING', 0, now())")
                    .param(UUID.randomUUID()).param(SHARED_STORE).update();
        }
        document(tenant, document, documentStore, gross);
    }

    private static void document(UUID tenant, UUID document, UUID store, String gross) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("INSERT INTO reporting.sales_record (document_id, store_id, business_date, local_date, "
                    + "local_hour, issued_at, on_account, series_code, number, non_fiscal, currency, total_gross, "
                    + "total_net, total_tax, projected_at) VALUES (?, ?, DATE '" + DAY + "', DATE '" + DAY + "', 12, "
                    + "now(), false, 'ISO-SERIES', 1, false, 'MZN', ?, ?, 0, now())")
                    .param(document).param(store).param(new java.math.BigDecimal(gross))
                    .param(new java.math.BigDecimal(gross)).update();
            app.jdbc().sql("INSERT INTO reporting.sales_record_line (document_id, line_no, store_id, business_date, "
                    + "local_hour, description, quantity, gross, net, tax, tax_rate, projected_at) VALUES (?, 1, ?, "
                    + "DATE '" + DAY + "', 12, 'Cola', 1, ?, ?, 0, 0, now())")
                    .param(document).param(store).param(new java.math.BigDecimal(gross))
                    .param(new java.math.BigDecimal(gross)).update();
        }
    }

    @Test
    void bothTenantsRebuildTheSameStoreIdAtOnce() {
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT tenant_id FROM reporting.rebuild_run "
                        + "WHERE store_id = ? AND status = 'RUNNING'").param(SHARED_STORE).query(UUID.class).list())
                .containsExactlyInAnyOrder(A, B);
    }

    @Test
    void aTenantSeesOnlyItsOwnRows() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThat(app.jdbc().sql("SELECT document_id FROM reporting.sales_record WHERE series_code = 'ISO-SERIES'")
                    .query(UUID.class).list()).containsExactly(A_DOCUMENT);
            assertThat(app.jdbc().sql("SELECT count(*) FROM reporting.sales_record_line WHERE document_id = ?")
                    .param(B_LEAK).query(Long.class).single()).isZero();
        }
    }

    @Test
    void aReportSumsOnlyTheTenantsRowsAndAnotherTenantsStoreIs404() throws Exception {
        mvc.perform(get("/api/v1/backoffice/reports/sales").param("storeId", A_STORE.toString())
                        .param("from", DAY).param("to", DAY).param("groupBy", "day")
                        .with(in(A, A_STORE, Permission.REPORT_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.documents").value(1))
                .andExpect(jsonPath("$.totals.gross.amount").value(10.0));
        mvc.perform(get("/api/v1/backoffice/reports/sales").param("storeId", B_STORE.toString())
                        .param("from", DAY).param("to", DAY).param("groupBy", "day")
                        .with(in(A, null, Permission.REPORT_VIEW)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRowWithAnotherTenantsIdIsRefused() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("INSERT INTO reporting.credit_record (charge_id, "
                    + "tenant_id, store_id, business_date, customer_id, order_id, amount, projected_at) VALUES "
                    + "(?, ?, ?, DATE '" + DAY + "', ?, ?, 1, now())").param(UUID.randomUUID()).param(B)
                    .param(A_STORE).param(UUID.randomUUID()).param(UUID.randomUUID()).update(),
                    StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        }
    }

    @Test
    void aRowNeverMovesToAnotherTenant() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE reporting.sales_record SET tenant_id = ? "
                    + "WHERE document_id = ?").param(B).param(A_DOCUMENT).update(),
                    StoreScopeViolations.Kind.STORE_CHANGE);
        }
    }

    @Test
    void aChildCannotReferenceAnotherTenantsParent() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThatThrownBy(() -> app.jdbc().sql("INSERT INTO reporting.sales_record_line (document_id, line_no, "
                    + "store_id, business_date, local_hour, description, quantity, gross, net, tax, tax_rate, "
                    + "projected_at) VALUES (?, 2, ?, DATE '" + DAY + "', 12, 'Forged', 1, 1, 1, 0, 0, now())")
                    .param(B_DOCUMENT).param(A_STORE).update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
        }
    }

    @Test
    void anUnboundReadFailsClosed() {
        try (AppRoleSession app = AppRoleSession.open().noTenant().allStores()) {
            for (String table : TABLES) {
                AppRoleSession.assertRefused(() -> app.jdbc().sql("SELECT count(*) FROM reporting." + table)
                        .query(Long.class).single(), StoreScopeViolations.Kind.TENANT_MISSING);
            }
        }
    }

    @Test
    void theModuleIsTenantProtected() {
        RowLevelSecurityCoverageTest.assertModuleTenantProtected("reporting");
    }

    /** An account of {@code tenant} (its resolved membership as the principal's details), in a store or everywhere. */
    private static RequestPostProcessor in(UUID tenant, UUID store, Permission permission) {
        String authority = store == null ? permission.authority() : permission.authority(store);
        Actor actor = Actor.account(UUID.randomUUID(), Set.of(authority));
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                List.of(new SimpleGrantedAuthority(authority)));
        token.setDetails(new TenantContext(tenant, MembershipKind.ACCOUNT, actor.userId(), "test:isolation"));
        return authentication(token);
    }
}
