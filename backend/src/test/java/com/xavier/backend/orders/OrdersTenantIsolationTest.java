package com.xavier.backend.orders;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A80, A82 and A84 for orders: tenants A and B use the same delivery-zone code side by side. Through the API an
 * order of B addressed by id from A is absent (404, never 403) and an order of another store of A is forbidden (403,
 * D42); the list shows only the caller's tenant. With no {@code AccessPolicy} in front (the application-role seam),
 * B's rows are invisible to A, a row with B's {@code tenant_id} is refused ({@code 42501}), a row never moves
 * tenant ({@code RS002}), a child cannot reference B's parent (composite foreign key, proved as the owner so that
 * the row policies do not answer first), and an unbound read raises {@code RS003}. Strict: no bootstrap fallback.
 */
@OrdersModuleTest
@StrictTenantScope
class OrdersTenantIsolationTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    static final UUID A_STORE = UUID.fromString("0190b400-0000-7000-8000-0000000000c1");
    static final UUID A_OTHER_STORE = UUID.fromString("0190b400-0000-7000-8000-0000000000c2");
    static final UUID B_STORE = UUID.fromString("0190b400-0000-7000-8000-0000000000d1");
    static final UUID A_ORDER = UUID.fromString("0190b400-0000-7000-8000-0000000002a1");
    static final UUID A_OTHER_ORDER = UUID.fromString("0190b400-0000-7000-8000-0000000002a2");
    static final UUID A_EMPTY_ORDER = UUID.fromString("0190b400-0000-7000-8000-0000000002a3");
    static final UUID B_ORDER = UUID.fromString("0190b400-0000-7000-8000-0000000002b1");
    static final UUID A_LINE = UUID.fromString("0190b400-0000-7000-8000-0000000003a1");
    static final UUID B_LINE = UUID.fromString("0190b400-0000-7000-8000-0000000003b1");
    static final UUID A_ZONE = UUID.fromString("0190b400-0000-7000-8000-0000000004a1");
    static final UUID B_ZONE = UUID.fromString("0190b400-0000-7000-8000-0000000004b1");

    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void twoTenantsWithIdenticalCodes() {
        TenantTestSupport.ensureTenants();
        seed(A, A_STORE, A_ORDER, A_LINE, A_ZONE);
        seed(B, B_STORE, B_ORDER, B_LINE, B_ZONE);
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            insertOrder(app.jdbc(), A_OTHER_ORDER, A_OTHER_STORE);
            insertOrder(app.jdbc(), A_EMPTY_ORDER, A_STORE);
        }
    }

    /** As the application role in {@code tenant}: no tenant_id is written, the column default takes the bound one. */
    private static void seed(UUID tenant, UUID store, UUID order, UUID line, UUID zone) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("""
                    INSERT INTO organization.store (id, code, name, timezone, active, version, created_at, updated_at)
                    VALUES (?, 'OR-1', 'Baixa', 'Africa/Maputo', true, 0, now(), now())""").param(store).update();
            insertOrder(app.jdbc(), order, store);
            app.jdbc().sql("""
                    INSERT INTO orders.order_line (id, order_id, status, item_id, quantity, created_at,
                        created_by_employee_id, version)
                    VALUES (?, ?, 'DRAFT', gen_random_uuid(), 1, now(), gen_random_uuid(), 0)""")
                    .param(line).param(order).update();
            app.jdbc().sql("""
                    INSERT INTO orders.delivery_zone (id, store_id, code, name, fee_item_id, active, version)
                    VALUES (?, ?, 'ISO-Z', 'Zone', gen_random_uuid(), true, 0)""").param(zone).param(store).update();
        }
    }

    private static void insertOrder(JdbcClient jdbc, UUID order, UUID store) {
        jdbc.sql("""
                INSERT INTO orders.order_header (id, store_id, terminal_id, order_number, mode, table_id, lifecycle,
                    billing_lock, business_day, currency, opened_at, opened_by_employee_id, next_batch_no, version)
                VALUES (?, ?, gen_random_uuid(), nextval('orders.order_number_seq'), 'COUNTER', NULL, 'OPEN',
                    'NONE', current_date, 'MZN', now(), gen_random_uuid(), 1, 0)""").param(order).param(store).update();
    }

    @Test
    void bothTenantsUseTheSameZoneCode() {
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT tenant_id FROM orders.delivery_zone WHERE code = 'ISO-Z'")
                .query(UUID.class).list()).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void aTenantSeesOnlyItsOwnRows() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThat(app.jdbc().sql("SELECT id FROM orders.order_header WHERE id IN (?, ?, ?)").param(A_ORDER)
                    .param(A_OTHER_ORDER).param(B_ORDER).query(UUID.class).list())
                    .containsExactlyInAnyOrder(A_ORDER, A_OTHER_ORDER);
            assertThat(app.jdbc().sql("SELECT id FROM orders.order_line WHERE id IN (?, ?)").param(A_LINE)
                    .param(B_LINE).query(UUID.class).list()).containsExactly(A_LINE);
            assertThat(app.jdbc().sql("SELECT id FROM orders.delivery_zone WHERE code = 'ISO-Z'").query(UUID.class)
                    .list()).containsExactly(A_ZONE);
        }
    }

    @Test
    void anotherTenantsOrderById404AndOwnOrder200() throws Exception {
        mvc.perform(get("/api/v1/pos/orders/" + B_ORDER).with(pos(A, A_STORE))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/pos/orders/" + A_EMPTY_ORDER).with(pos(A, A_STORE)))
                .andExpect(status().isOk());
    }

    @Test
    void anotherStoreOfTheTenant403() throws Exception {
        mvc.perform(get("/api/v1/pos/orders/" + A_OTHER_ORDER).with(pos(A, A_STORE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theOpenOrdersListShowsOnlyTheCallersTenant() throws Exception {
        mvc.perform(get("/api/v1/pos/orders").with(pos(B, B_STORE))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + B_ORDER + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.id == '" + A_ORDER + "')]").doesNotExist());
    }

    @Test
    void aRowWithAnotherTenantsIdIsRefused() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("""
                    INSERT INTO orders.delivery_zone (id, tenant_id, store_id, code, name, fee_item_id, active,
                        version)
                    VALUES (?, ?, ?, 'ISO-X', 'X', gen_random_uuid(), true, 0)""").param(UUID.randomUUID())
                    .param(B).param(A_STORE).update(), StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        }
    }

    @Test
    void aRowNeverMovesToAnotherTenant() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE orders.order_header SET tenant_id = ? "
                    + "WHERE id = ?").param(B).param(A_ORDER).update(), StoreScopeViolations.Kind.STORE_CHANGE);
        }
    }

    @Test
    void aChildCannotReferenceAnotherTenantsParent() {
        assertThatThrownBy(() -> OwnerDatabase.shared().jdbc().sql("""
                INSERT INTO orders.order_line (id, tenant_id, order_id, status, item_id, quantity, created_at,
                    created_by_employee_id, version)
                VALUES (?, ?, ?, 'DRAFT', gen_random_uuid(), 1, now(), gen_random_uuid(), 0)""")
                .param(UUID.randomUUID()).param(A).param(B_ORDER).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
    }

    @Test
    void anUnboundReadFailsClosed() {
        try (AppRoleSession app = AppRoleSession.open().noTenant().allStores()) {
            for (String table : List.of("order_header", "order_line", "delivery_zone", "delivery_event")) {
                AppRoleSession.assertRefused(() -> app.jdbc().sql("SELECT count(*) FROM orders." + table)
                        .query(Long.class).single(), StoreScopeViolations.Kind.TENANT_MISSING);
            }
        }
    }

    @Test
    void theModuleIsTenantProtected() {
        RowLevelSecurityCoverageTest.assertModuleTenantProtected("orders");
        CompositeForeignKeyCoverageTest.assertModuleKeysIncludeTheTenant("orders");
    }

    /** A POS session of {@code tenant} in {@code store} (its resolved membership as the principal's details). */
    private static RequestPostProcessor pos(UUID tenant, UUID store) {
        Set<String> authorities = Set.of(Permission.ORDER_VIEW.authority(store));
        Actor actor = Actor.employee(UUID.randomUUID(), UUID.randomUUID(), store, authorities);
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        token.setDetails(new TenantContext(tenant, MembershipKind.TERMINAL, actor.userId(), "test:isolation"));
        return authentication(token);
    }
}
