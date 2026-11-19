package com.xavier.backend.fiscal;

import static com.xavier.backend.fiscal.FiscalFixtures.asEmployee;
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
import com.xavier.backend.access.TestAuthentications;
import com.xavier.backend.architecture.CompositeForeignKeyCoverageTest;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A80, A82 and A84 for fiscal: tenants A and B with the same series code and the same source order. The same series
 * code in A and B issues independent numbers (each tenant's series starts where it was created, and one never
 * advances the other). Through the API, B's series addressed by id from A is absent (404). With no
 * {@code AccessPolicy} in front (the application-role seam), B's rows are invisible to A, a row with B's
 * {@code tenant_id} is refused ({@code 42501}), a row never moves tenant ({@code RS002}), a child cannot reference B's
 * parent (composite foreign key, {@code 23503}; or the parent policy, {@code 42501}), and an unbound read raises
 * {@code RS003}. Strict: no bootstrap-tenant fallback.
 */
@FiscalModuleTest
@StrictTenantScope
class FiscalTenantIsolationTest {

    static final Permission PERMISSION = Permission.FISCAL_MANAGE;
    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    // drawn per run: the shared database also holds other suites' stores and sources
    static UUID A_STORE;
    static UUID B_STORE;
    static UUID SHARED_SOURCE;

    static final List<String> TABLES = List.of("fiscal_series", "fiscal_document", "fiscal_document_line",
            "fiscal_document_tax", "fiscal_document_payment", "fiscal_document_print");

    record Seed(UUID store, UUID series, UUID document) {
    }

    static Seed a;
    static Seed b;

    @Autowired
    MockMvc mvc;
    @Autowired
    FiscalApi fiscal;
    @Autowired
    FiscalFixtures fixtures;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TenantScopes tenants;

    @BeforeAll
    static void twoTenantsWithTheSameSeriesCode() {
        TenantTestSupport.ensureTenants();
        A_STORE = UUID.randomUUID();
        B_STORE = UUID.randomUUID();
        SHARED_SOURCE = UUID.randomUUID();
        String run = A_STORE.toString().substring(24);
        a = seed(A, A_STORE, "Company A", "FISC-A-" + run);
        b = seed(B, B_STORE, "Company B", "FISC-B-" + run);
    }

    /** As the application role in {@code tenant}: no tenant_id is written, the column default takes the bound one. */
    private static Seed seed(UUID tenant, UUID store, String company, String storeCode) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            JdbcClient db = app.jdbc();
            db.sql("INSERT INTO organization.company_profile (id, name, tax_id, country, currency, version, "
                    + "created_at, updated_at) VALUES (?, ?, 'TAX-ISO', 'MZ', 'MZN', 0, now(), now()) "
                    + "ON CONFLICT (tenant_id) DO NOTHING").param(UUID.randomUUID()).param(company).update();
            db.sql("INSERT INTO organization.store (id, code, name, timezone, active, version, created_at, "
                    + "updated_at) VALUES (?, ?, 'Baixa', 'Africa/Maputo', true, 0, now(), now())")
                    .param(store).param(storeCode).update();
            Seed seed = new Seed(store, UUID.randomUUID(), UUID.randomUUID());
            db.sql("INSERT INTO fiscal.fiscal_series (id, store_id, document_type, code, first_number, next_number, "
                    + "active, version, created_at, updated_at) VALUES (?, ?, 'SALE', 'ISO', 1, 1, false, 0, now(), "
                    + "now())").param(seed.series()).param(store).update();
            db.sql("""
                    INSERT INTO fiscal.fiscal_document (id, store_id, document_type, status, source_type, source_id,
                        currency, total_gross, total_net, total_tax, company_name, company_tax_id, company_country,
                        store_code, store_name, store_timezone, version, created_at, updated_at)
                    VALUES (?, ?, 'SALE', 'DRAFT', 'ORDER', ?, 'MZN', 116.00, 100.00, 16.00, ?, 'TAX-ISO', 'MZ', ?,
                        'Baixa', 'Africa/Maputo', 0, now(), now())""")
                    .params(seed.document(), store, SHARED_SOURCE, company, storeCode).update();
            db.sql("""
                    INSERT INTO fiscal.fiscal_document_line (id, document_id, line_no, description, quantity,
                        unit_price, tax_rate, gross, net, tax)
                    VALUES (?, ?, 1, 'Cerveja', 2, 58.00, 16, 116.00, 100.00, 16.00)""")
                    .params(UUID.randomUUID(), seed.document()).update();
            db.sql("INSERT INTO fiscal.fiscal_document_tax (id, document_id, tax_rate, net, tax) "
                    + "VALUES (?, ?, 16, 100.00, 16.00)").params(UUID.randomUUID(), seed.document()).update();
            db.sql("INSERT INTO fiscal.fiscal_document_payment (document_id, position, method_code, label, amount) "
                    + "VALUES (?, 1, 'CASH', 'Cash', 116.00)").param(seed.document()).update();
            db.sql("INSERT INTO fiscal.fiscal_document_print (id, document_id, copy_no, requested_at, "
                    + "requested_by_employee_id, terminal_id) VALUES (?, ?, 1, now(), ?, ?)")
                    .params(UUID.randomUUID(), seed.document(), UUID.randomUUID(), UUID.randomUUID()).update();
            return seed;
        }
    }

    @Test
    void bothTenantsUseTheSameSeriesCodeAndSourceOrder() {
        JdbcClient owner = OwnerDatabase.shared().jdbc();
        assertThat(owner.sql("SELECT tenant_id FROM fiscal.fiscal_series WHERE code = 'ISO' AND store_id IN (?, ?)")
                .param(A_STORE).param(B_STORE).query(UUID.class).list()).containsExactlyInAnyOrder(A, B);
        assertThat(owner.sql("SELECT tenant_id FROM fiscal.fiscal_document WHERE source_id = ?")
                .param(SHARED_SOURCE).query(UUID.class).list()).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void aTenantSeesOnlyItsOwnRowsInListsAndById() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThat(app.jdbc().sql("SELECT id FROM fiscal.fiscal_document WHERE source_id = ?")
                    .param(SHARED_SOURCE).query(UUID.class).list()).containsExactly(a.document());
            assertThat(app.jdbc().sql("SELECT count(*) FROM fiscal.fiscal_series WHERE id = ?").param(b.series())
                    .query(Long.class).single()).isZero();
            for (String child : List.of("fiscal_document_line", "fiscal_document_tax", "fiscal_document_payment",
                    "fiscal_document_print")) {
                assertThat(app.jdbc().sql("SELECT count(*) FROM fiscal." + child + " WHERE document_id = ?")
                        .param(b.document()).query(Long.class).single()).as(child).isZero();
            }
        }
    }

    @Test
    void aBulkReportCoversOnlyTheCallersTenant() {
        try (AppRoleSession inA = AppRoleSession.open().tenant(A).allStores()) {
            assertThat(inA.jdbc().sql("SELECT count(*) FROM fiscal.fiscal_document WHERE store_id IN (?, ?)")
                    .param(A_STORE).param(B_STORE).query(Long.class).single()).isEqualTo(1L);
        }
    }

    /** The same series code, created in A with first number 1 and in B with 500, never shares a number. */
    @Test
    void theSameSeriesCodeInTwoTenantsIssuesIndependentNumbers() throws Exception {
        UUID seriesA = createSeries(A, A_STORE, "S1", 1);
        UUID seriesB = createSeries(B, B_STORE, "S1", 500);
        UUID employee = UUID.randomUUID();
        UUID terminal = UUID.randomUUID();

        long first = issue(A, A_STORE, employee, terminal);
        long firstOfB = issue(B, B_STORE, employee, terminal);
        long second = issue(A, A_STORE, employee, terminal);

        assertThat(List.of(first, second)).containsExactly(1L, 2L);
        assertThat(firstOfB).isEqualTo(500L);
        assertThat(nextNumber(seriesA)).isEqualTo(3L);
        assertThat(nextNumber(seriesB)).isEqualTo(501L);
    }

    @Test
    void anotherTenantsSeriesById404AndEachTenantListsItsOwn() throws Exception {
        mvc.perform(post("/api/v1/backoffice/fiscal/series/" + b.series() + "/deactivate").with(in(A)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/backoffice/fiscal/series").param("storeId", A_STORE.toString()).with(in(A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == 'ISO')].id").value(a.series().toString()));
        mvc.perform(get("/api/v1/backoffice/fiscal/series").param("storeId", B_STORE.toString()).with(in(A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[?(@.code == 'ISO')]").isEmpty());
    }

    @Test
    void aRowWithAnotherTenantsIdIsRefused() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("INSERT INTO fiscal.fiscal_series (id, tenant_id, "
                    + "store_id, document_type, code, first_number, next_number, active, version, created_at, "
                    + "updated_at) VALUES (?, ?, ?, 'SALE', 'X', 1, 1, false, 0, now(), now())")
                    .param(UUID.randomUUID()).param(B).param(A_STORE).update(),
                    StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        }
    }

    @Test
    void aRowNeverMovesToAnotherTenant() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE fiscal.fiscal_series SET tenant_id = ? "
                    + "WHERE id = ?").param(B).param(a.series()).update(), StoreScopeViolations.Kind.STORE_CHANGE);
        }
    }

    @Test
    void aChildCannotReferenceAnotherTenantsParent() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            // a STORE table: the composite foreign key (tenant_id, series_id) finds no series in A
            assertThatThrownBy(() -> app.jdbc().sql("""
                    INSERT INTO fiscal.fiscal_document (id, store_id, document_type, status, source_type, source_id,
                        series_id, series_code, number, currency, total_gross, total_net, total_tax, company_name,
                        company_tax_id, company_country, store_code, store_name, store_timezone, adapter_code,
                        non_fiscal, issued_at, version, created_at, updated_at)
                    VALUES (?, ?, 'SALE', 'ISSUED', 'ORDER', ?, ?, 'ISO', 1, 'MZN', 116.00, 100.00, 16.00, 'C', 'T',
                        'MZ', 'FISC-A', 'Baixa', 'Africa/Maputo', 'INTERNAL', true, now(), 0, now(), now())""")
                    .params(UUID.randomUUID(), A_STORE, UUID.randomUUID(), b.series()).update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
            // a CHILD table: B's document is not even visible, so the parent policy refuses first
            AppRoleSession.assertRefused(() -> app.jdbc().sql("INSERT INTO fiscal.fiscal_document_tax (id, "
                    + "document_id, tax_rate, net, tax) VALUES (?, ?, 5, 1.00, 0.05)")
                    .params(UUID.randomUUID(), b.document()).update(),
                    StoreScopeViolations.Kind.WRITE_REFUSED);
        }
    }

    @Test
    void anUnboundReadFailsClosed() {
        try (AppRoleSession app = AppRoleSession.open().noTenant().allStores()) {
            for (String table : TABLES) {
                AppRoleSession.assertRefused(() -> app.jdbc().sql("SELECT count(*) FROM fiscal." + table)
                        .query(Long.class).single(), StoreScopeViolations.Kind.TENANT_MISSING);
            }
        }
    }

    /** FE-F35: the series code is the module's only code key, and it compares the code ignoring case. */
    @Test
    void theSeriesCodeKeyComparesTheCodeIgnoringCase() {
        List<String> definitions = OwnerDatabase.shared().jdbc().sql("SELECT indexname || ' ' || indexdef "
                        + "FROM pg_indexes WHERE schemaname = 'fiscal' "
                        + "AND indexdef LIKE 'CREATE UNIQUE INDEX%' AND indexdef ~ '[(, ]code[),]'")
                .query(String.class).list();

        assertThat(definitions).singleElement().asString().startsWith("fiscal_series_code_uk ")
                .contains("(tenant_id, store_id, document_type, upper((code)::text))");
    }

    @Test
    void aCaseVariantOfASeriesCodeInTheSameStoreIsRefusedByTheDatabase() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThatThrownBy(() -> app.jdbc().sql("INSERT INTO fiscal.fiscal_series (id, store_id, document_type, "
                    + "code, first_number, next_number, active, version, created_at, updated_at) VALUES (?, ?, "
                    + "'SALE', 'iso', 1, 1, false, 0, now(), now())").param(UUID.randomUUID()).param(A_STORE)
                    .update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23505"))
                    .hasMessageContaining("fiscal_series_code_uk");
        }
    }

    @Test
    void theModuleIsTenantProtected() {
        RowLevelSecurityCoverageTest.assertModuleTenantProtected("fiscal");
        CompositeForeignKeyCoverageTest.assertModuleKeysIncludeTheTenant("fiscal");
    }

    private UUID createSeries(UUID tenant, UUID store, String code, long firstNumber) throws Exception {
        String body = mvc.perform(post("/api/v1/backoffice/fiscal/series").with(in(tenant)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeId\": \"%s\", \"code\": \"%s\", \"firstNumber\": %d}".formatted(store, code,
                        firstNumber)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return FiscalFixtures.id(body);
    }

    /** A paid draft of {@code store} issued as an employee, all inside {@code tenant}; the number it got. */
    private long issue(UUID tenant, UUID store, UUID employee, UUID terminal) {
        return TenantTestSupport.inTenant(tenants, tenant, () -> {
            UUID draft = fixtures.newPaidDraft(store);
            return asEmployee(store, employee, terminal, () -> fiscal.issue(draft, employee)).number();
        });
    }

    private long nextNumber(UUID series) {
        return OwnerDatabase.shared().jdbc().sql("SELECT next_number FROM fiscal.fiscal_series WHERE id = ?")
                .param(series).query(Long.class).single();
    }

    /** An installation-wide account of {@code tenant} (its resolved membership as the principal's details). */
    private static RequestPostProcessor in(UUID tenant) {
        Actor actor = Actor.account(UUID.randomUUID(), Set.of(PERMISSION.authority()));
        return authentication(TestAuthentications.of(actor, tenant));
    }
}
