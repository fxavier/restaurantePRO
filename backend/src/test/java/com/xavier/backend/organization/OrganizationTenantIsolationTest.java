package com.xavier.backend.organization;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A84 (organization) and A82: tenants A and B with identical store and terminal codes. Through the API, B's store and
 * terminal addressed by id from A are absent (404, not 403), and another store of A is forbidden (403, D42). With no
 * {@code AccessPolicy} in front (the application-role seam), B's rows are invisible to A, a row with B's
 * {@code tenant_id} is refused ({@code 42501}), a row never moves tenant ({@code RS002}), a child cannot reference
 * B's parent (composite foreign key), and an unbound read raises {@code RS003}. Each tenant has its own company
 * profile. Strict: no bootstrap-tenant fallback.
 */
@OrganizationModuleTest
@StrictTenantScope
class OrganizationTenantIsolationTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    static final UUID A_STORE = UUID.fromString("0190b400-0000-7000-8000-0000000000a1");
    static final UUID A_OTHER_STORE = UUID.fromString("0190b400-0000-7000-8000-0000000000a2");
    static final UUID B_STORE = UUID.fromString("0190b400-0000-7000-8000-0000000000b1");
    static final UUID A_TERMINAL = UUID.fromString("0190b400-0000-7000-8000-0000000001a1");
    static final UUID A_OTHER_TERMINAL = UUID.fromString("0190b400-0000-7000-8000-0000000001a2");
    static final UUID B_TERMINAL = UUID.fromString("0190b400-0000-7000-8000-0000000001b1");

    @Autowired
    MockMvc mvc;
    @Autowired
    OrganizationApi organization;
    @Autowired
    TenantScopes tenants;

    @BeforeAll
    static void twoTenantsWithIdenticalCodes() {
        TenantTestSupport.ensureTenants();
        seed(A, "Company A", A_STORE, A_TERMINAL);
        seed(B, "Company B", B_STORE, B_TERMINAL);
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            app.jdbc().sql("INSERT INTO organization.store (id, code, name, timezone, active, version, created_at, "
                    + "updated_at) VALUES (?, 'ISO-2', 'Other', 'Africa/Maputo', true, 0, now(), now())")
                    .param(A_OTHER_STORE).update();
            app.jdbc().sql("INSERT INTO organization.terminal (id, store_id, code, name, status, version, created_at, "
                    + "updated_at) VALUES (?, ?, 'T1', 'Till', 'REGISTERED', 0, now(), now())")
                    .param(A_OTHER_TERMINAL).param(A_OTHER_STORE).update();
        }
    }

    /**
     * As the application role in {@code tenant}: no tenant_id is written, the column default takes the bound one. The
     * company name is upserted: other isolation suites seed tenants A and B's company too (as "Company"), and the one
     * that runs first would otherwise win.
     */
    private static void seed(UUID tenant, String company, UUID store, UUID terminal) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("INSERT INTO organization.company_profile (id, name, tax_id, country, currency, version, "
                    + "created_at, updated_at) VALUES (?, ?, 'TAX-ISO', 'MZ', 'MZN', 0, now(), now()) "
                    + "ON CONFLICT (tenant_id) DO UPDATE SET name = excluded.name").param(UUID.randomUUID())
                    .param(company).update();
            app.jdbc().sql("INSERT INTO organization.store (id, code, name, timezone, active, version, created_at, "
                    + "updated_at) VALUES (?, 'ISO-1', 'Baixa', 'Africa/Maputo', true, 0, now(), now())")
                    .param(store).update();
            app.jdbc().sql("INSERT INTO organization.terminal (id, store_id, code, name, status, version, created_at, "
                    + "updated_at) VALUES (?, ?, 'T1', 'Till', 'REGISTERED', 0, now(), now())")
                    .param(terminal).param(store).update();
        }
    }

    @Test
    void bothTenantsUseTheSameStoreAndTerminalCodes() {
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT s.tenant_id FROM organization.store s "
                        + "JOIN organization.terminal t ON t.store_id = s.id AND t.code = 'T1' WHERE s.code = 'ISO-1'")
                .query(UUID.class).list()).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void aTenantSeesOnlyItsOwnRows() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThat(app.jdbc().sql("SELECT id FROM organization.store WHERE code LIKE 'ISO-%'").query(UUID.class)
                    .list()).containsExactlyInAnyOrder(A_STORE, A_OTHER_STORE);
            assertThat(app.jdbc().sql("SELECT count(*) FROM organization.terminal WHERE id = ?").param(B_TERMINAL)
                    .query(Long.class).single()).isZero();
            assertThat(app.jdbc().sql("SELECT name FROM organization.company_profile").query(String.class).list())
                    .containsExactly("Company A");
        }
    }

    @Test
    void anotherTenantsStoreById404AndOwnStore200() throws Exception {
        mvc.perform(get("/api/v1/backoffice/stores/" + B_STORE).with(in(A, null, Permission.STORE_MANAGE)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/backoffice/stores/" + A_STORE).with(in(A, null, Permission.STORE_MANAGE)))
                .andExpect(status().isOk());
    }

    @Test
    void theStoreListNeverShowsAnotherTenantsStore() throws Exception {
        mvc.perform(get("/api/v1/backoffice/stores").param("size", "200").with(in(A, null, Permission.CATALOG_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(A_STORE)).exists())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(A_OTHER_STORE)).exists())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(B_STORE)).doesNotExist());
        mvc.perform(get("/api/v1/backoffice/stores").with(in(A, B_STORE, Permission.CATALOG_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void anotherStoreOfTheTenant403AndAnotherTenantsTerminal404() throws Exception {
        RequestPostProcessor managerOfAStore = in(A, A_STORE, Permission.TERMINAL_MANAGE);

        mvc.perform(post("/api/v1/backoffice/terminals/" + A_OTHER_TERMINAL + "/enrollment-code")
                .with(managerOfAStore).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/backoffice/terminals/" + B_TERMINAL + "/enrollment-code")
                .with(in(A, null, Permission.TERMINAL_MANAGE)).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void eachTenantReadsItsOwnCompanyProfile() {
        assertThat(TenantTestSupport.inTenant(tenants, A, () -> organization.companyProfile().name()))
                .isEqualTo("Company A");
        assertThat(TenantTestSupport.inTenant(tenants, B, () -> organization.companyProfile().name()))
                .isEqualTo("Company B");
        assertThat(TenantTestSupport.inTenant(tenants, A, () -> organization.findStore(B_STORE))).isEmpty();
    }

    @Test
    void aRowWithAnotherTenantsIdIsRefused() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("INSERT INTO organization.store (id, tenant_id, code, "
                    + "name, timezone, active, version, created_at, updated_at) VALUES (?, ?, 'ISO-X', 'X', "
                    + "'Africa/Maputo', true, 0, now(), now())").param(UUID.randomUUID()).param(B).update(),
                    StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        }
    }

    @Test
    void aRowNeverMovesToAnotherTenant() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE organization.store SET tenant_id = ? "
                    + "WHERE id = ?").param(B).param(A_STORE).update(), StoreScopeViolations.Kind.STORE_CHANGE);
        }
    }

    @Test
    void aChildCannotReferenceAnotherTenantsParent() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThatThrownBy(() -> app.jdbc().sql("INSERT INTO organization.terminal (id, store_id, code, name, "
                    + "status, version, created_at, updated_at) VALUES (?, ?, 'T9', 'Forged', 'REGISTERED', 0, now(), "
                    + "now())").param(UUID.randomUUID()).param(B_STORE).update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
        }
    }

    @Test
    void anUnboundReadFailsClosed() {
        try (AppRoleSession app = AppRoleSession.open().noTenant().allStores()) {
            for (String table : List.of("company_profile", "store", "terminal")) {
                AppRoleSession.assertRefused(() -> app.jdbc().sql("SELECT count(*) FROM organization." + table)
                        .query(Long.class).single(), StoreScopeViolations.Kind.TENANT_MISSING);
            }
        }
    }

    /** FE-F21: the database refuses a case variant of a code in the same store, whatever wrote it. */
    @Test
    void aCaseVariantOfATerminalCodeInTheSameStoreIsRefusedByTheDatabase() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThatThrownBy(() -> app.jdbc().sql("INSERT INTO organization.terminal (id, store_id, code, name, "
                    + "status, version, created_at, updated_at) VALUES (?, ?, 't1', 'Till', 'REGISTERED', 0, now(), "
                    + "now())").param(UUID.randomUUID()).param(A_STORE).update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23505"))
                    .hasMessageContaining("terminal_store_code_uk");
        }
    }

    @Test
    void theModuleIsTenantProtected() {
        RowLevelSecurityCoverageTest.assertModuleTenantProtected("organization");
    }

    /** An account of {@code tenant} (its resolved membership as the principal's details), in a store or everywhere. */
    private static RequestPostProcessor in(UUID tenant, UUID store, Permission permission) {
        String authority = store == null ? permission.authority() : permission.authority(store);
        Actor actor = Actor.account(UUID.randomUUID(), Set.of(authority));
        return authentication(TestAuthentications.of(actor, tenant));
    }
}
