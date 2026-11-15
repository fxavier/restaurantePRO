package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A82 (D84, D185): a rebuild requested in tenant A runs on the executor in tenant A, inside transactions bound to it.
 * It clears and re-projects A's rows of the store and range, and leaves untouched the rows tenant B holds for the same
 * store id and dates (a store id is only unique within a tenant's own rows once the tenant policy applies). The run it
 * records is A's. Strict: no bootstrap-tenant fallback on the test thread.
 */
@ReportingModuleTest
@StrictTenantScope
class RebuildRunnerTenantTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    static final UUID STORE = UUID.fromString("0190ba00-0000-7000-8000-0000000000a1");
    static final UUID A_DOCUMENT = UUID.fromString("0190ba00-0000-7000-8000-0000000001a1");
    static final UUID B_DOCUMENT = UUID.fromString("0190ba00-0000-7000-8000-0000000001b1");
    static final String DAY = "2026-02-02";

    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void bothTenantsProjectedTheSameStoreAndDay() {
        TenantTestSupport.ensureTenants();
        company(A, "Company A", STORE);
        company(B, "Company B", null); // B holds projections under the store id of A's, but no such store
        document(A, A_DOCUMENT);
        document(B, B_DOCUMENT);
    }

    private static void company(UUID tenant, String name, UUID store) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("INSERT INTO organization.company_profile (id, name, tax_id, country, currency, version, "
                    + "created_at, updated_at) VALUES (?, ?, 'TAX-ISO', 'MZ', 'MZN', 0, now(), now()) "
                    + "ON CONFLICT (tenant_id) DO NOTHING").param(UUID.randomUUID()).param(name).update();
            if (store != null) {
                app.jdbc().sql("INSERT INTO organization.store (id, code, name, timezone, active, version, "
                        + "created_at, updated_at) VALUES (?, 'RB-1', 'Baixa', 'Africa/Maputo', true, 0, now(), "
                        + "now())").param(store).update();
            }
        }
    }

    private static void document(UUID tenant, UUID document) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("INSERT INTO reporting.sales_record (document_id, store_id, business_date, local_date, "
                    + "local_hour, issued_at, on_account, series_code, number, non_fiscal, currency, total_gross, "
                    + "total_net, total_tax, projected_at) VALUES (?, ?, DATE '" + DAY + "', DATE '" + DAY + "', 12, "
                    + "now(), false, 'RB-SERIES', 1, false, 'MZN', ?, ?, 0, now())")
                    .param(document).param(STORE).param(new BigDecimal("5.00")).param(new BigDecimal("5.00"))
                    .update();
        }
    }

    @Test
    void aRebuildOfTenantAClearsItsRowsAndLeavesTenantBsRowsOfTheSameStoreAndDay() throws Exception {
        mvc.perform(post("/api/v1/backoffice/reports/rebuild").header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(csrf()).with(in(A, STORE, Permission.REPORT_REBUILD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\": \"%s\", \"from\": \"%s\", \"to\": \"%s\"}".formatted(STORE, DAY, DAY)))
                .andExpect(status().isAccepted());

        ReportingFixtures.await("tenant A's rebuild finished", () -> OwnerDatabase.shared().jdbc().sql(
                "SELECT count(*) FROM reporting.rebuild_run WHERE store_id = ? AND status = 'COMPLETED'")
                .param(STORE).query(Long.class).single() == 1);

        assertThat(count("SELECT count(*) FROM reporting.sales_record WHERE document_id = ?", A_DOCUMENT))
                .as("A's projection of the range was cleared").isZero();
        assertThat(count("SELECT count(*) FROM reporting.sales_record WHERE document_id = ?", B_DOCUMENT))
                .as("B's projection of the same store and day is untouched").isOne();
    }

    private static long count(String sql, UUID id) {
        return OwnerDatabase.shared().jdbc().sql(sql).param(id).query(Long.class).single();
    }

    private static RequestPostProcessor in(UUID tenant, UUID store, Permission permission) {
        String authority = permission.authority(store);
        Actor actor = Actor.account(UUID.randomUUID(), Set.of(authority));
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                List.of(new SimpleGrantedAuthority(authority)));
        token.setDetails(new TenantContext(tenant, MembershipKind.ACCOUNT, actor.userId(), "test:isolation"));
        return authentication(token);
    }
}
