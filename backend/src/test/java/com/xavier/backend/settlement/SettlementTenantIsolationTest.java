package com.xavier.backend.settlement;

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
import com.xavier.backend.shared.persistence.StoreScopeViolations;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A80, A82 and A84 for settlement: tenants A and B with identical payment-method codes, statement files (same method
 * and hash), order ids and tax-rounding lines. Through the API, B's payment method addressed by id from A is absent
 * (404) and each tenant lists only its own. With no {@code AccessPolicy} in front (the application-role seam), B's
 * rows are invisible to A in lists, by id and in a bulk sum, a row with B's {@code tenant_id} is refused
 * ({@code 42501}), a row never moves tenant ({@code RS002}), a child cannot reference B's parent (composite foreign
 * key, {@code 23503}; or the parent policy, {@code 42501}), and an unbound read raises {@code RS003}. Strict: no
 * bootstrap-tenant fallback.
 */
@SettlementModuleTest
@StrictTenantScope
class SettlementTenantIsolationTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    static final UUID A_STORE = UUID.fromString("0190b500-0000-7000-8000-0000000000a1");
    static final UUID B_STORE = UUID.fromString("0190b500-0000-7000-8000-0000000000b1");
    static final UUID SHARED_ORDER = UUID.fromString("0190b500-0000-7000-8000-000000000f01");
    static final UUID SHARED_LINE = UUID.fromString("0190b500-0000-7000-8000-000000000f02");
    static final String SHA = "a".repeat(64);

    static final List<String> TABLES = List.of("payment_method", "order_settlement", "payment_attempt", "payment",
            "payment_allocation", "refund", "bill_partition", "bill_partition_line", "credit_charge",
            "reconciliation_batch", "reconciliation_entry", "reconciliation_resolution", "tax_rounding_adjustment",
            "tax_correction_resolution");

    record Seed(UUID method, UUID attempt, UUID payment, UUID partition, UUID batch) {
    }

    static Seed a;
    static Seed b;

    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void twoTenantsWithIdenticalCodes() {
        TenantTestSupport.ensureTenants();
        a = seed(A, A_STORE, "10.00");
        b = seed(B, B_STORE, "20.00");
    }

    /** As the application role in {@code tenant}: no tenant_id is written, the column default takes the bound one. */
    private static Seed seed(UUID tenant, UUID store, String amount) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            JdbcClient jdbc = app.jdbc();
            Seed seed = new Seed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID());
            jdbc.sql("INSERT INTO settlement.payment_method (id, code, name, kind, active, created_at, updated_at, "
                    + "version) VALUES (?, 'ISO_M', 'Isolation', 'EXTERNAL_TERMINAL', true, now(), now(), 0)")
                    .param(seed.method()).update();
            jdbc.sql("INSERT INTO settlement.order_settlement (order_id, created_at) VALUES (?, now())")
                    .param(SHARED_ORDER).update();
            app.inTransaction(() -> {
                jdbc.sql("""
                        INSERT INTO settlement.payment_attempt (id, order_id, store_id, terminal_id, session_id,
                            method_code, method_kind, amount, tendered, change_given, currency, status,
                            created_by_employee_id, created_at, updated_at, version)
                        VALUES (?, ?, ?, ?, ?, 'CASH', 'CASH', ?, ?, 0, 'MZN', 'SUCCEEDED', ?, now(), now(), 0)""")
                        .params(seed.attempt(), UUID.randomUUID(), store, UUID.randomUUID(), UUID.randomUUID(),
                                new BigDecimal(amount), new BigDecimal(amount), UUID.randomUUID()).update();
                jdbc.sql("""
                        INSERT INTO settlement.payment (id, attempt_id, order_id, store_id, session_id, method_code,
                            amount, currency, status, collected_by_employee_id, confirmed_at)
                        SELECT ?, a.id, a.order_id, ?, a.session_id, 'CASH', ?, 'MZN', 'CONFIRMED', ?, now()
                        FROM settlement.payment_attempt a WHERE a.id = ?""")
                        .params(seed.payment(), store, new BigDecimal(amount), UUID.randomUUID(), seed.attempt())
                        .update();
                jdbc.sql("""
                        INSERT INTO settlement.payment_allocation (id, payment_id, target_type, target_id, amount,
                            currency, created_at) VALUES (?, ?, 'ORDER', ?, ?, 'MZN', now())""")
                        .params(UUID.randomUUID(), seed.payment(), UUID.randomUUID(), new BigDecimal(amount))
                        .update();
            });
            jdbc.sql("""
                    INSERT INTO settlement.bill_partition (id, order_id, store_id, kind, amount, currency, status,
                        created_by_employee_id, created_at, version)
                    VALUES (?, ?, ?, 'AMOUNT', 5.00, 'MZN', 'LIVE', ?, now(), 0)""")
                    .params(seed.partition(), SHARED_ORDER, store, UUID.randomUUID()).update();
            jdbc.sql("""
                    INSERT INTO settlement.reconciliation_batch (id, store_id, method_code, period_from, period_to,
                        currency, file_sha256, source, line_count, imported_by_account_id, imported_at)
                    VALUES (?, ?, 'ISO_M', current_date, current_date, 'MZN', ?, 'CSV', 0, ?, now())""")
                    .params(seed.batch(), store, SHA, UUID.randomUUID()).update();
            jdbc.sql("""
                    INSERT INTO settlement.tax_rounding_adjustment (id, order_id, store_id, business_date,
                        source_line_id, rate_percent, amount, currency, kind, document_id, actor_kind, created_at)
                    VALUES (?, ?, ?, current_date, ?, 16, 0.01, 'MZN', 'PENDING_CORRECTION', ?, 'SYSTEM', now())""")
                    .params(UUID.randomUUID(), SHARED_ORDER, store, SHARED_LINE, UUID.randomUUID()).update();
            return seed;
        }
    }

    @Test
    void bothTenantsUseTheSameCodesKeysAndOrderIds() {
        JdbcClient owner = OwnerDatabase.shared().jdbc();
        assertThat(owner.sql("SELECT tenant_id FROM settlement.payment_method WHERE code = 'ISO_M'")
                .query(UUID.class).list()).containsExactlyInAnyOrder(A, B);
        assertThat(owner.sql("SELECT tenant_id FROM settlement.reconciliation_batch WHERE file_sha256 = ?")
                .param(SHA).query(UUID.class).list()).containsExactlyInAnyOrder(A, B);
        assertThat(owner.sql("SELECT tenant_id FROM settlement.tax_rounding_adjustment WHERE order_id = ? "
                + "AND source_line_id = ?").param(SHARED_ORDER).param(SHARED_LINE).query(UUID.class).list())
                .containsExactlyInAnyOrder(A, B);
        assertThat(owner.sql("SELECT tenant_id FROM settlement.order_settlement WHERE order_id = ?")
                .param(SHARED_ORDER).query(UUID.class).list()).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void aTenantSeesOnlyItsOwnRowsInListsAndById() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThat(app.jdbc().sql("SELECT id FROM settlement.payment_method WHERE code = 'ISO_M'")
                    .query(UUID.class).list()).containsExactly(a.method());
            assertThat(app.jdbc().sql("SELECT id FROM settlement.reconciliation_batch WHERE file_sha256 = ?")
                    .param(SHA).query(UUID.class).list()).containsExactly(a.batch());
            assertThat(app.jdbc().sql("SELECT count(*) FROM settlement.payment WHERE id = ?").param(b.payment())
                    .query(Long.class).single()).isZero();
            assertThat(app.jdbc().sql("SELECT count(*) FROM settlement.payment_allocation WHERE payment_id = ?")
                    .param(b.payment()).query(Long.class).single()).isZero();
            assertThat(app.jdbc().sql("SELECT count(*) FROM settlement.order_settlement WHERE order_id = ?")
                    .param(SHARED_ORDER).query(Long.class).single()).isEqualTo(1L);
        }
    }

    @Test
    void aBulkReportCoversOnlyTheCallersTenant() {
        String sum = "SELECT sum(amount) FROM settlement.payment WHERE id IN (?, ?)";
        try (AppRoleSession inA = AppRoleSession.open().tenant(A).allStores();
                AppRoleSession inB = AppRoleSession.open().tenant(B).allStores()) {
            assertThat(inA.jdbc().sql(sum).param(a.payment()).param(b.payment()).query(BigDecimal.class).single())
                    .isEqualByComparingTo("10.00");
            assertThat(inB.jdbc().sql(sum).param(a.payment()).param(b.payment()).query(BigDecimal.class).single())
                    .isEqualByComparingTo("20.00");
        }
    }

    @Test
    void anotherTenantsPaymentMethodById404AndEachTenantListsItsOwn() throws Exception {
        mvc.perform(put("/api/v1/backoffice/payment-methods/" + b.method()).with(in(A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Hijack\", \"active\": true, \"version\": 0}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/backoffice/payment-methods").with(in(A))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'ISO_M')].id").value(a.method().toString()));
        mvc.perform(get("/api/v1/backoffice/payment-methods").with(in(B))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'ISO_M')].id").value(b.method().toString()));
    }

    @Test
    void theSameNewMethodCodeIsFreeInEveryTenant() throws Exception {
        String body = "{\"code\": \"ISO_NEW\", \"name\": \"New\", \"kind\": \"EXTERNAL_TERMINAL\"}";
        mvc.perform(post("/api/v1/backoffice/payment-methods").with(in(A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/backoffice/payment-methods").with(in(B)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/backoffice/payment-methods").with(in(A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }

    @Test
    void aRowWithAnotherTenantsIdIsRefused() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("INSERT INTO settlement.payment_method (id, tenant_id, "
                    + "code, name, kind, active, created_at, updated_at, version) VALUES (?, ?, 'ISO_X', 'X', "
                    + "'EXTERNAL_TERMINAL', true, now(), now(), 0)").param(UUID.randomUUID()).param(B).update(),
                    StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        }
    }

    @Test
    void aRowNeverMovesToAnotherTenant() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE settlement.payment_method SET tenant_id = ? "
                    + "WHERE id = ?").param(B).param(a.method()).update(), StoreScopeViolations.Kind.STORE_CHANGE);
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE settlement.payment_attempt SET tenant_id = ? "
                    + "WHERE id = ?").param(B).param(a.attempt()).update(), StoreScopeViolations.Kind.STORE_CHANGE);
        }
    }

    @Test
    void aChildCannotReferenceAnotherTenantsParent() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            // STORE tables: the composite foreign key (tenant_id, parent) finds no parent in A
            assertThatThrownBy(() -> app.jdbc().sql("""
                    INSERT INTO settlement.credit_charge (id, order_id, partition_id, target_type, store_id,
                        terminal_id, customer_id, amount, currency, business_date, status, created_by_employee_id,
                        created_at, version)
                    VALUES (?, ?, ?, 'PARTITION', ?, ?, ?, 1.00, 'MZN', current_date, 'RESERVED', ?, now(), 0)""")
                    .params(UUID.randomUUID(), SHARED_ORDER, b.partition(), A_STORE, UUID.randomUUID(),
                            UUID.randomUUID(), UUID.randomUUID()).update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
            assertThatThrownBy(() -> app.inTransaction(() -> app.jdbc().sql("""
                    INSERT INTO settlement.refund (id, payment_id, store_id, target_type, order_id, method_code,
                        amount, currency, reason, session_id, terminal_id, business_date, operator_employee_id,
                        approver_employee_id, confirmed_at)
                    VALUES (?, ?, ?, 'ORDER', ?, 'CASH', 1.00, 'MZN', 'forged', ?, ?, current_date, ?, ?, now())""")
                    .params(UUID.randomUUID(), b.payment(), A_STORE, SHARED_ORDER, UUID.randomUUID(),
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).update()))
                    .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isIn("23503", "42501"));
            // CHILD table: its parent is not even visible, so the parent policy refuses first
            AppRoleSession.assertRefused(() -> app.jdbc().sql("""
                    INSERT INTO settlement.payment_allocation (id, payment_id, target_type, target_id, amount,
                        currency, created_at) VALUES (?, ?, 'ORDER', ?, 1.00, 'MZN', now())""")
                    .params(UUID.randomUUID(), b.payment(), UUID.randomUUID()).update(),
                    StoreScopeViolations.Kind.WRITE_REFUSED);
        }
    }

    @Test
    void anUnboundReadFailsClosed() {
        try (AppRoleSession app = AppRoleSession.open().noTenant().allStores()) {
            for (String table : TABLES) {
                AppRoleSession.assertRefused(() -> app.jdbc().sql("SELECT count(*) FROM settlement." + table)
                        .query(Long.class).single(), StoreScopeViolations.Kind.TENANT_MISSING);
            }
        }
    }

    @Test
    void theModuleIsTenantProtected() {
        RowLevelSecurityCoverageTest.assertModuleTenantProtected("settlement");
        TenantKeyChecks.assertKeysIncludeTheTenant("settlement");
    }

    /** An installation-wide account of {@code tenant} (its resolved membership as the principal's details). */
    private static RequestPostProcessor in(UUID tenant) {
        String authority = Permission.PAYMENT_METHOD_MANAGE.authority();
        Actor actor = Actor.account(UUID.randomUUID(), Set.of(authority));
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                List.of(new SimpleGrantedAuthority(authority)));
        token.setDetails(new TenantContext(tenant, MembershipKind.ACCOUNT, actor.userId(), "test:isolation"));
        return authentication(token);
    }
}
