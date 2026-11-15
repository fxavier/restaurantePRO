package com.xavier.backend.dining;

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
import com.xavier.backend.orders.TenantKeyAssertions;
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
 * A80, A82 and A84 for dining: tenants A and B use the same area, table and store codes side by side. Through the API
 * a table of B addressed by id from A is absent (404, never 403), a table of another store of A is forbidden (403,
 * D42) and a list of B's store from A is empty. With no {@code AccessPolicy} in front (the application-role seam),
 * B's rows are invisible to A, a row with B's {@code tenant_id} is refused ({@code 42501}), a row never moves
 * tenant ({@code RS002}), a child cannot reference B's parent (composite foreign key, proved as the owner so that the
 * row policies do not answer first), and an unbound read raises {@code RS003}. Strict: no bootstrap fallback.
 */
@DiningModuleTest
@StrictTenantScope
class DiningTenantIsolationTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    static final UUID A_STORE = UUID.fromString("0190b400-0000-7000-8000-0000000000e1");
    static final UUID A_OTHER_STORE = UUID.fromString("0190b400-0000-7000-8000-0000000000e2");
    static final UUID B_STORE = UUID.fromString("0190b400-0000-7000-8000-0000000000f1");
    static final UUID A_AREA = UUID.fromString("0190b400-0000-7000-8000-0000000005a1");
    static final UUID A_OTHER_AREA = UUID.fromString("0190b400-0000-7000-8000-0000000005a2");
    static final UUID B_AREA = UUID.fromString("0190b400-0000-7000-8000-0000000005b1");
    static final UUID A_TABLE = UUID.fromString("0190b400-0000-7000-8000-0000000006a1");
    static final UUID A_OTHER_TABLE = UUID.fromString("0190b400-0000-7000-8000-0000000006a2");
    static final UUID B_TABLE = UUID.fromString("0190b400-0000-7000-8000-0000000006b1");

    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void twoTenantsWithIdenticalCodes() {
        TenantTestSupport.ensureTenants();
        seed(A, A_STORE, A_AREA, A_TABLE);
        seed(B, B_STORE, B_AREA, B_TABLE);
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            insertArea(app, A_OTHER_AREA, A_OTHER_STORE);
            insertTable(app, A_OTHER_TABLE, A_OTHER_STORE, A_OTHER_AREA);
        }
    }

    /** As the application role in {@code tenant}: no tenant_id is written, the column default takes the bound one. */
    private static void seed(UUID tenant, UUID store, UUID area, UUID table) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("""
                    INSERT INTO organization.store (id, code, name, timezone, active, version, created_at, updated_at)
                    VALUES (?, 'DN-1', 'Baixa', 'Africa/Maputo', true, 0, now(), now())""").param(store).update();
            insertArea(app, area, store);
            insertTable(app, table, store, area);
            app.jdbc().sql("INSERT INTO dining.dining_settings (store_id, updated_at, version) VALUES (?, now(), 0)")
                    .param(store).update();
        }
    }

    private static void insertArea(AppRoleSession app, UUID area, UUID store) {
        app.jdbc().sql("""
                INSERT INTO dining.room_area (id, store_id, code, name, active, version, created_at, updated_at)
                VALUES (?, ?, 'SALA', 'Sala', true, 0, now(), now())""").param(area).param(store).update();
    }

    private static void insertTable(AppRoleSession app, UUID table, UUID store, UUID area) {
        app.jdbc().sql("""
                INSERT INTO dining.restaurant_table (id, store_id, area_id, code, seats, active, version, created_at,
                    updated_at)
                VALUES (?, ?, ?, 'M1', 4, true, 0, now(), now())""").param(table).param(store).param(area).update();
    }

    @Test
    void bothTenantsUseTheSameAreaAndTableCodes() {
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT t.tenant_id FROM dining.restaurant_table t "
                        + "JOIN dining.room_area a ON a.id = t.area_id WHERE t.code = 'M1' AND a.code = 'SALA' "
                        + "AND t.store_id IN (?, ?)").param(A_STORE).param(B_STORE).query(UUID.class).list())
                .containsExactlyInAnyOrder(A, B);
    }

    @Test
    void aTenantSeesOnlyItsOwnRows() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThat(app.jdbc().sql("SELECT id FROM dining.restaurant_table WHERE code = 'M1'").query(UUID.class)
                    .list()).containsExactlyInAnyOrder(A_TABLE, A_OTHER_TABLE);
            assertThat(app.jdbc().sql("SELECT count(*) FROM dining.room_area WHERE id = ?").param(B_AREA)
                    .query(Long.class).single()).isZero();
            assertThat(app.jdbc().sql("SELECT store_id FROM dining.dining_settings WHERE store_id IN (?, ?)")
                    .param(A_STORE).param(B_STORE).query(UUID.class).list()).containsExactly(A_STORE);
        }
    }

    @Test
    void anotherTenantsTableById404AndOwnTable200() throws Exception {
        mvc.perform(get("/api/v1/backoffice/tables/" + B_TABLE).with(manager(A, null)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/backoffice/tables/" + A_TABLE).with(manager(A, null))).andExpect(status().isOk());
    }

    @Test
    void anotherStoreOfTheTenant403() throws Exception {
        mvc.perform(get("/api/v1/backoffice/tables/" + A_OTHER_TABLE).with(manager(A, A_STORE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theListOfAnotherTenantsStoreIsEmptyAndOwnIsNot() throws Exception {
        mvc.perform(get("/api/v1/backoffice/tables").param("storeId", B_STORE.toString()).with(manager(A, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/api/v1/backoffice/tables").param("storeId", A_STORE.toString()).with(manager(A, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(A_TABLE.toString()));
    }

    @Test
    void aRowWithAnotherTenantsIdIsRefused() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("""
                    INSERT INTO dining.room_area (id, tenant_id, store_id, code, name, active, version, created_at,
                        updated_at)
                    VALUES (?, ?, ?, 'SALA-X', 'X', true, 0, now(), now())""").param(UUID.randomUUID()).param(B)
                    .param(A_STORE).update(), StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        }
    }

    @Test
    void aRowNeverMovesToAnotherTenant() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE dining.restaurant_table SET tenant_id = ? "
                    + "WHERE id = ?").param(B).param(A_TABLE).update(), StoreScopeViolations.Kind.STORE_CHANGE);
        }
    }

    @Test
    void aChildCannotReferenceAnotherTenantsParent() {
        assertThatThrownBy(() -> OwnerDatabase.shared().jdbc().sql("""
                INSERT INTO dining.restaurant_table (id, tenant_id, store_id, area_id, code, seats, active, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 'M9', 2, true, 0, now(), now())""")
                .param(UUID.randomUUID()).param(A).param(B_STORE).param(B_AREA).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
    }

    @Test
    void anUnboundReadFailsClosed() {
        try (AppRoleSession app = AppRoleSession.open().noTenant().allStores()) {
            for (String table : List.of("room_area", "restaurant_table", "table_occupation", "reservation",
                    "reservation_table", "dining_settings")) {
                AppRoleSession.assertRefused(() -> app.jdbc().sql("SELECT count(*) FROM dining." + table)
                        .query(Long.class).single(), StoreScopeViolations.Kind.TENANT_MISSING);
            }
        }
    }

    @Test
    void theModuleIsTenantProtected() {
        RowLevelSecurityCoverageTest.assertModuleTenantProtected("dining");
        TenantKeyAssertions.assertModuleKeysIncludeTheTenant("dining");
    }

    /** A BackOffice account of {@code tenant} managing dining in {@code store}, or everywhere when null. */
    private static RequestPostProcessor manager(UUID tenant, UUID store) {
        String authority = store == null ? Permission.DINING_MANAGE.authority()
                : Permission.DINING_MANAGE.authority(store);
        Actor actor = Actor.account(UUID.randomUUID(), Set.of(authority));
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                List.of(new SimpleGrantedAuthority(authority)));
        token.setDetails(new TenantContext(tenant, MembershipKind.ACCOUNT, actor.userId(), "test:isolation"));
        return authentication(token);
    }
}
