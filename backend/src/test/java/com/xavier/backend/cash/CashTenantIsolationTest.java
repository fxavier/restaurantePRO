package com.xavier.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.settlement.TenantKeyChecks;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A80, A82 and A84 for cash: tenants A and B with identical register codes and business dates. Through the API, B's
 * register addressed by id from A is absent (404, not 403) and each tenant opens a register with the same code. With
 * no {@code AccessPolicy} in front (the application-role seam), B's rows are invisible to A, a row with B's
 * {@code tenant_id} is refused ({@code 42501}), a row never moves tenant ({@code RS002}), a child cannot reference B's
 * parent (composite foreign key, {@code 23503}; or the parent policy, {@code 42501}), and an unbound read raises
 * {@code RS003}. Strict: no bootstrap-tenant fallback.
 */
@CashModuleTest
@StrictTenantScope
class CashTenantIsolationTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    static final UUID A_STORE = UUID.fromString("0190b600-0000-7000-8000-0000000000a1");
    static final UUID B_STORE = UUID.fromString("0190b600-0000-7000-8000-0000000000b1");
    static final LocalDate DAY = LocalDate.of(2026, 11, 4);

    static final List<String> TABLES = List.of("cash_register", "cash_day", "cash_day_reopening", "cash_session",
            "cash_session_count", "cash_movement", "cash_closing", "cash_closing_line");

    record Seed(UUID store, UUID register, UUID day, UUID session, UUID closing) {
    }

    static Seed a;
    static Seed b;

    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void twoTenantsWithIdenticalCodes() {
        TenantTestSupport.ensureTenants();
        a = seed(A, A_STORE, "Company A");
        b = seed(B, B_STORE, "Company B");
    }

    /** As the application role in {@code tenant}: no tenant_id is written, the column default takes the bound one. */
    private static Seed seed(UUID tenant, UUID store, String company) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            JdbcClient jdbc = app.jdbc();
            jdbc.sql("INSERT INTO organization.company_profile (id, name, tax_id, country, currency, version, "
                    + "created_at, updated_at) VALUES (?, ?, 'TAX-ISO', 'MZ', 'MZN', 0, now(), now()) "
                    + "ON CONFLICT (tenant_id) DO NOTHING").param(UUID.randomUUID()).param(company).update();
            jdbc.sql("INSERT INTO organization.store (id, code, name, timezone, active, version, created_at, "
                    + "updated_at) VALUES (?, 'CASH-1', 'Baixa', 'Africa/Maputo', true, 0, now(), now())")
                    .param(store).update();
            Seed seed = new Seed(store, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            jdbc.sql("INSERT INTO cash.cash_register (id, store_id, code, name, active, version, created_at, "
                    + "updated_at) VALUES (?, ?, 'R1', 'Till', true, 0, now(), now())")
                    .param(seed.register()).param(store).update();
            jdbc.sql("INSERT INTO cash.cash_day (id, store_id, business_date, status, version, opened_at) "
                    + "VALUES (?, ?, ?, 'OPEN', 0, now())").param(seed.day()).param(store).param(DAY).update();
            jdbc.sql("""
                    INSERT INTO cash.cash_session (id, store_id, register_id, cash_day_id, operator_employee_id,
                        terminal_id, status, opening_float, currency, opened_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, 'OPEN', 0, 'MZN', now(), 0)""")
                    .params(seed.session(), store, seed.register(), seed.day(), UUID.randomUUID(), UUID.randomUUID())
                    .update();
            jdbc.sql("""
                    INSERT INTO cash.cash_movement (id, store_id, cash_day_id, session_id, register_id, type,
                        direction, method_code, amount, currency, reason, actor_employee_id, occurred_at)
                    VALUES (?, ?, ?, ?, ?, 'CASH_IN', 'IN', 'CASH', 5.00, 'MZN', 'float', ?, now())""")
                    .params(UUID.randomUUID(), store, seed.day(), seed.session(), seed.register(),
                            UUID.randomUUID()).update();
            jdbc.sql("""
                    INSERT INTO cash.cash_closing (id, store_id, register_id, cash_day_id, attempt_no, status,
                        version, currency, created_at, created_by_employee_id, updated_at)
                    VALUES (?, ?, ?, ?, 1, 'DRAFT', 0, 'MZN', now(), ?, now())""")
                    .params(seed.closing(), store, seed.register(), seed.day(), UUID.randomUUID()).update();
            jdbc.sql("""
                    INSERT INTO cash.cash_closing_line (id, closing_id, method_code, expected_amount,
                        counted_amount, difference_amount, currency) VALUES (?, ?, 'CASH', 5.00, 5.00, 0, 'MZN')""")
                    .params(UUID.randomUUID(), seed.closing()).update();
            jdbc.sql("""
                    INSERT INTO cash.cash_session_count (id, session_id, method_code, amount, currency, counted_at)
                    VALUES (?, ?, 'CASH', 5.00, 'MZN', now())""").params(UUID.randomUUID(), seed.session()).update();
            return seed;
        }
    }

    @Test
    void bothTenantsUseTheSameRegisterCodeAndBusinessDate() {
        JdbcClient owner = OwnerDatabase.shared().jdbc();
        assertThat(owner.sql("SELECT r.tenant_id FROM cash.cash_register r WHERE r.code = 'R1' AND r.store_id IN (?, ?)")
                .param(A_STORE).param(B_STORE).query(UUID.class).list()).containsExactlyInAnyOrder(A, B);
        assertThat(owner.sql("SELECT d.tenant_id FROM cash.cash_day d WHERE d.business_date = ? "
                + "AND d.store_id IN (?, ?)").param(DAY).param(A_STORE).param(B_STORE).query(UUID.class).list())
                .containsExactlyInAnyOrder(A, B);
    }

    @Test
    void aTenantSeesOnlyItsOwnRowsInListsAndById() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThat(app.jdbc().sql("SELECT id FROM cash.cash_register WHERE code = 'R1' AND store_id IN (?, ?)")
                    .param(A_STORE).param(B_STORE).query(UUID.class).list()).containsExactly(a.register());
            assertThat(app.jdbc().sql("SELECT count(*) FROM cash.cash_session WHERE id = ?").param(b.session())
                    .query(Long.class).single()).isZero();
            assertThat(app.jdbc().sql("SELECT count(*) FROM cash.cash_session_count WHERE session_id = ?")
                    .param(b.session()).query(Long.class).single()).isZero();
            assertThat(app.jdbc().sql("SELECT count(*) FROM cash.cash_closing_line WHERE closing_id = ?")
                    .param(b.closing()).query(Long.class).single()).isZero();
        }
    }

    @Test
    void aBulkReportCoversOnlyTheCallersTenant() {
        String sum = "SELECT count(*) FROM cash.cash_movement WHERE store_id IN (?, ?)";
        try (AppRoleSession inA = AppRoleSession.open().tenant(A).allStores()) {
            assertThat(inA.jdbc().sql(sum).param(A_STORE).param(B_STORE).query(Long.class).single()).isEqualTo(1L);
        }
    }

    @Test
    void anotherTenantsRegisterById404AndEachTenantOpensTheSameCode() throws Exception {
        mvc.perform(put("/api/v1/backoffice/cash/registers/" + b.register()).with(in(A)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"R1\", \"name\": \"Hijack\", \"active\": true, \"version\": 0}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/backoffice/cash/registers").param("storeId", A_STORE.toString()).with(in(A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[?(@.code == 'R1')].id")
                        .value(a.register().toString()));
        for (UUID tenant : List.of(A, B)) {
            UUID store = tenant.equals(A) ? A_STORE : B_STORE;
            mvc.perform(post("/api/v1/backoffice/cash/registers").with(in(tenant)).with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"storeId\": \"" + store + "\", \"code\": \"R2\", \"name\": \"Second\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/v1/backoffice/cash/registers").with(in(A)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeId\": \"" + B_STORE + "\", \"code\": \"R3\", \"name\": \"Not mine\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRowWithAnotherTenantsIdIsRefused() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("INSERT INTO cash.cash_register (id, tenant_id, "
                    + "store_id, code, name, active, version, created_at, updated_at) VALUES (?, ?, ?, 'RX', 'X', "
                    + "true, 0, now(), now())").param(UUID.randomUUID()).param(B).param(A_STORE).update(),
                    StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        }
    }

    @Test
    void aRowNeverMovesToAnotherTenant() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE cash.cash_register SET tenant_id = ? "
                    + "WHERE id = ?").param(B).param(a.register()).update(), StoreScopeViolations.Kind.STORE_CHANGE);
        }
    }

    @Test
    void aChildCannotReferenceAnotherTenantsParent() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            // a STORE table: the composite foreign key (tenant_id, register_id, store_id) finds no register in A
            assertThatThrownBy(() -> app.jdbc().sql("""
                    INSERT INTO cash.cash_closing (id, store_id, register_id, cash_day_id, attempt_no, status,
                        version, currency, created_at, created_by_employee_id, updated_at, day_round)
                    VALUES (?, ?, ?, ?, 1, 'DRAFT', 0, 'MZN', now(), ?, now(), 8)""")
                    .params(UUID.randomUUID(), A_STORE, b.register(), a.day(), UUID.randomUUID()).update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
            // a CHILD table: B's session is not even visible, so the parent policy refuses first
            AppRoleSession.assertRefused(() -> app.jdbc().sql("""
                    INSERT INTO cash.cash_session_count (id, session_id, method_code, amount, currency, counted_at)
                    VALUES (?, ?, 'MBWAY', 1.00, 'MZN', now())""").params(UUID.randomUUID(), b.session()).update(),
                    StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        }
    }

    @Test
    void anUnboundReadFailsClosed() {
        try (AppRoleSession app = AppRoleSession.open().noTenant().allStores()) {
            for (String table : TABLES) {
                AppRoleSession.assertRefused(() -> app.jdbc().sql("SELECT count(*) FROM cash." + table)
                        .query(Long.class).single(), StoreScopeViolations.Kind.TENANT_MISSING);
            }
        }
    }

    @Test
    void theModuleIsTenantProtected() {
        RowLevelSecurityCoverageTest.assertModuleTenantProtected("cash");
        TenantKeyChecks.assertKeysIncludeTheTenant("cash");
    }

    /** An installation-wide account of {@code tenant} (its resolved membership as the principal's details). */
    private static RequestPostProcessor in(UUID tenant) {
        String authority = Permission.CASH_REGISTER_MANAGE.authority();
        Actor actor = Actor.account(UUID.randomUUID(), Set.of(authority));
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                List.of(new SimpleGrantedAuthority(authority)));
        token.setDetails(new TenantContext(tenant, MembershipKind.ACCOUNT, actor.userId(), "test:isolation"));
        return authentication(token);
    }
}
