package com.xavier.backend.inventory;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static com.xavier.backend.inventory.InventoryFixtures.json;
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
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A80, A82, A84 and A88 for inventory (R6, S74c): tenants A and B use the same stock item, warehouse and store codes
 * side by side. Through the API B's rows addressed by id from A are absent (404, never 403), lists and reports show
 * only A's, and a transfer never crosses tenants. With no {@code AccessPolicy} in front (the application-role seam),
 * B's rows are invisible to A, a row with B's {@code tenant_id} is refused ({@code 42501}), a row never moves tenant
 * ({@code RS002}), a child cannot reference B's parent (composite foreign key), an unbound read raises {@code RS003},
 * and the transfer probe {@code inventory.stock_transfer_stores} answers only for the bound tenant. Strict: no
 * bootstrap-tenant fallback.
 */
@InventoryModuleTest
@StrictTenantScope
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryTenantIsolationTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;
    static final UUID ACCOUNT = UUID.fromString("0190b400-0000-7000-8000-0000000000d7");

    /** What one tenant owns: two stores, a warehouse in each (same code), one item, and a draft transfer. */
    record Owned(UUID store, UUID otherStore, UUID warehouse, UUID otherWarehouse, UUID item, UUID transfer) {
    }

    @Autowired
    MockMvc mvc;

    Owned a;
    Owned b;

    @BeforeAll
    void twoTenantsWithIdenticalCodes() throws Exception {
        TenantTestSupport.ensureTenants();
        company(A);
        company(B);
        a = seed(A, "1.0000");
        b = seed(B, "2.0000");
    }

    private static void company(UUID tenant) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("INSERT INTO organization.company_profile (id, name, tax_id, country, currency, version, "
                    + "created_at, updated_at) VALUES (?, 'Company', 'TAX-ISO', 'MZ', 'MZN', 0, now(), now()) "
                    + "ON CONFLICT (tenant_id) DO NOTHING").param(UUID.randomUUID()).update();
        }
    }

    /** The same codes in every tenant: stores ISO-1/ISO-2, warehouses MAIN, item FLOUR; costs tell the tenants apart. */
    private Owned seed(UUID tenant, String unitCost) throws Exception {
        UUID store = store(tenant, "ISO-1");
        UUID otherStore = store(tenant, "ISO-2");
        for (UUID each : List.of(store, otherStore)) {
            mvc.perform(idem(post(BO + "/activations")).with(in(tenant, each, Permission.INVENTORY_MANAGE))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\": \"%s\"}".formatted(each)))
                    .andExpect(status().isCreated());
        }
        UUID warehouse = warehouse(tenant, store);
        UUID otherWarehouse = warehouse(tenant, otherStore);
        UUID item = id(mvc.perform(idem(post(BO + "/stock-items")).with(in(tenant, null, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"code": "FLOUR", "name": "Flour", "baseUnit": "kg", "kind": "PURCHASED"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(idem(post(BO + "/movements/manual-adjustments"))
                        .with(in(tenant, store, Permission.INVENTORY_MOVE)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId": "%s", "reason": "start",
                                 "lines": [{"stockItemId": "%s", "quantity": 10, "unitCost": %s}]}"""
                                .formatted(warehouse, item, unitCost)))
                .andExpect(status().isCreated());
        UUID transfer = id(mvc.perform(idem(post(BO + "/transfers"))
                        .with(in(tenant, store, Permission.INVENTORY_TRANSFER, Permission.INVENTORY_VIEW))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"fromWarehouseId": "%s", "toWarehouseId": "%s",
                                 "lines": [{"stockItemId": "%s", "quantity": 1}]}""".formatted(warehouse,
                                otherWarehouse, item)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Owned(store, otherStore, warehouse, otherWarehouse, item, transfer);
    }

    private UUID store(UUID tenant, String code) throws Exception {
        return id(mvc.perform(post("/api/v1/backoffice/stores").with(in(tenant, null, Permission.STORE_MANAGE))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                                {"code": "%s", "name": "Store", "timezone": "Africa/Maputo",
                                 "businessDayCutoff": "05:00"}""".formatted(code)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID warehouse(UUID tenant, UUID store) throws Exception {
        return id(mvc.perform(idem(post(BO + "/warehouses")).with(in(tenant, null, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"storeId": "%s", "code": "MAIN", "name": "Store-room", "negativePolicy": "ALLOW"}"""
                                .formatted(store)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void bothTenantsUseTheSameItemAndWarehouseCodes() {
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT tenant_id FROM inventory.stock_item "
                + "WHERE code = 'FLOUR'").query(UUID.class).list()).containsExactlyInAnyOrder(A, B);
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT tenant_id FROM inventory.warehouse "
                        + "WHERE code = 'MAIN' AND id IN (?, ?, ?, ?)")
                .params(a.warehouse, a.otherWarehouse, b.warehouse, b.otherWarehouse).query(UUID.class).list())
                .containsExactlyInAnyOrder(A, A, B, B);
    }

    @Test
    void aTenantListsOnlyItsOwnItems() throws Exception {
        String body = mvc.perform(get(BO + "/stock-items").with(in(A, null, Permission.INVENTORY_VIEW)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(json(body).get("content").valueStream().map(row -> row.get("id").asString()).toList())
                .contains(a.item.toString()).doesNotContain(b.item.toString());
    }

    @Test
    void anotherTenantsRowsById404AndOwnRows200() throws Exception {
        RequestPostProcessor everywhere = in(A, null, Permission.INVENTORY_VIEW, Permission.INVENTORY_TRANSFER,
                Permission.INVENTORY_MANAGE);

        mvc.perform(get(BO + "/stock-items/" + b.item).with(everywhere)).andExpect(status().isNotFound());
        mvc.perform(get(BO + "/warehouses/" + b.warehouse).with(everywhere)).andExpect(status().isNotFound());
        mvc.perform(get(BO + "/transfers/" + b.transfer).with(everywhere)).andExpect(status().isNotFound());
        mvc.perform(get(BO + "/stock").param("warehouseId", b.warehouse.toString()).with(everywhere))
                .andExpect(status().isNotFound());
        mvc.perform(get(BO + "/stock-items/" + a.item).with(everywhere)).andExpect(status().isOk());
        mvc.perform(get(BO + "/warehouses/" + a.warehouse).with(everywhere)).andExpect(status().isOk());
        mvc.perform(get(BO + "/transfers/" + a.transfer).with(everywhere)).andExpect(status().isOk());
    }

    @Test
    void aBulkReportShowsOnlyTheTenantsRows() throws Exception {
        RequestPostProcessor viewer = in(A, null, Permission.INVENTORY_VIEW);

        mvc.perform(get(BO + "/reports/valuation").param("storeId", a.store.toString()).with(viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.totalValue").value(10.0));
        String foreign = mvc.perform(get(BO + "/reports/valuation").param("storeId", b.store.toString())
                        .with(viewer)).andReturn().getResponse().getContentAsString();
        assertThat(foreign).doesNotContain(b.item.toString()).doesNotContain(b.warehouse.toString())
                .doesNotContain("20.0");
    }

    @Test
    void aTransferNeverCrossesTenants() throws Exception {
        RequestPostProcessor manager = in(A, null, Permission.INVENTORY_TRANSFER, Permission.INVENTORY_VIEW);

        mvc.perform(idem(post(BO + "/transfers")).with(manager).contentType(MediaType.APPLICATION_JSON).content("""
                        {"fromWarehouseId": "%s", "toWarehouseId": "%s",
                         "lines": [{"stockItemId": "%s", "quantity": 1}]}""".formatted(a.warehouse, b.warehouse,
                        a.item))).andExpect(status().isNotFound());
        for (String command : List.of("ship", "cancel")) {
            mvc.perform(idem(post(BO + "/transfers/" + b.transfer + "/" + command)).with(manager))
                    .andExpect(status().isNotFound());
        }
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThatThrownBy(() -> app.jdbc().sql("""
                            INSERT INTO inventory.stock_transfer (id, from_warehouse_id, to_warehouse_id,
                                from_store_id, to_store_id, status, created_at, version)
                            VALUES (?, ?, ?, ?, ?, 'DRAFT', now(), 0)""")
                    .params(UUID.randomUUID(), a.warehouse, b.warehouse, a.store, b.store).update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
        }
    }

    @Test
    void theTransferProbeAnswersOnlyForTheBoundTenantAndIsNotPublic() {
        String probe = "SELECT from_store_id, to_store_id FROM inventory.stock_transfer_stores(?)";
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThat(app.jdbc().sql(probe).param(a.transfer).query().singleRow())
                    .isEqualTo(Map.of("from_store_id", a.store, "to_store_id", a.otherStore));
            assertThat(app.jdbc().sql(probe).param(b.transfer).query().listOfRows())
                    .as("another tenant's transfer reads as absent").isEmpty();
            app.noTenant();
            AppRoleSession.assertRefused(() -> app.jdbc().sql(probe).param(a.transfer).query().listOfRows(),
                    StoreScopeViolations.Kind.TENANT_MISSING);
        }
        assertThat(OwnerDatabase.shared().jdbc().sql("""
                        SELECT p.proacl IS NULL OR EXISTS (SELECT 1 FROM aclexplode(p.proacl) a
                                                           WHERE a.grantee = 0 AND a.privilege_type = 'EXECUTE')
                        FROM pg_catalog.pg_proc p WHERE p.oid = 'inventory.stock_transfer_stores(uuid)'::regprocedure""")
                .query(Boolean.class).single()).as("executable by PUBLIC").isFalse();
    }

    @Test
    void aTenantSeesOnlyItsOwnRowsIncludingTheOpenReadWarehouses() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).unset()) {
            assertThat(app.jdbc().sql("SELECT id FROM inventory.warehouse WHERE code = 'MAIN'").query(UUID.class)
                    .list()).as("open reads stop at the tenant").contains(a.warehouse, a.otherWarehouse)
                    .doesNotContain(b.warehouse, b.otherWarehouse);
        }
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThat(app.jdbc().sql("SELECT count(*) FROM inventory.stock_item WHERE id = ?").param(b.item)
                    .query(Long.class).single()).isZero();
            assertThat(app.jdbc().sql("SELECT count(*) FROM inventory.stock_balance WHERE warehouse_id = ?")
                    .param(b.warehouse).query(Long.class).single()).isZero();
            assertThat(app.jdbc().sql("SELECT count(*) FROM inventory.stock_movement WHERE warehouse_id = ?")
                    .param(b.warehouse).query(Long.class).single()).isZero();
            assertThat(app.jdbc().sql("SELECT count(*) FROM inventory.stock_transfer WHERE id = ?")
                    .param(b.transfer).query(Long.class).single()).isZero();
        }
    }

    @Test
    void aRowWithAnotherTenantsIdIsRefused() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("""
                            INSERT INTO inventory.stock_item (id, tenant_id, code, name, base_unit, kind, active, version,
                                created_at, updated_at) VALUES (?, ?, 'FORGED', 'X', 'kg', 'PURCHASED', true, 0, now(),
                                now())""").param(UUID.randomUUID()).param(B).update(),
                    StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        }
    }

    @Test
    void aRowNeverMovesToAnotherTenant() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE inventory.stock_item SET tenant_id = ? "
                    + "WHERE id = ?").param(B).param(a.item).update(), StoreScopeViolations.Kind.STORE_CHANGE);
        }
    }

    @Test
    void aChildCannotReferenceAnotherTenantsParent() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThatThrownBy(() -> app.jdbc().sql("INSERT INTO inventory.stock_item_pack (id, stock_item_id, code, "
                    + "name, quantity_base) VALUES (?, ?, 'BOX', 'Box', 12)").param(UUID.randomUUID())
                    .param(b.item).update()).isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
            assertThatThrownBy(() -> app.jdbc().sql("INSERT INTO inventory.stock_level (warehouse_id, stock_item_id, "
                    + "min_quantity, version) VALUES (?, ?, 1, 0)").param(a.warehouse).param(b.item).update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
        }
    }

    @Test
    void anUnboundReadFailsClosed() {
        try (AppRoleSession app = AppRoleSession.open().noTenant().allStores()) {
            for (String table : List.of("stock_item", "warehouse", "stock_movement", "stock_balance", "stock_level",
                    "stock_transfer", "stock_transfer_line", "recipe", "supplier", "purchase_order_line")) {
                AppRoleSession.assertRefused(() -> app.jdbc().sql("SELECT count(*) FROM inventory." + table)
                        .query(Long.class).single(), StoreScopeViolations.Kind.TENANT_MISSING);
            }
        }
    }

    @Test
    void theModuleIsTenantProtected() {
        RowLevelSecurityCoverageTest.assertModuleTenantProtected("inventory");
    }

    /** The stock units are a seeded code list, the same for every tenant: PLATFORM, with its reason. */
    @Test
    void theStockUnitCodeListIsPlatform() {
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT reason FROM shared.rls_platform_table "
                + "WHERE table_name = 'inventory.stock_unit'::regclass").query(String.class).list())
                .singleElement().asString().isNotBlank();
    }

    /** An account of {@code tenant} (its resolved membership as the principal's details), in a store or everywhere. */
    private static RequestPostProcessor in(UUID tenant, UUID store, Permission... permissions) {
        Set<String> authorities = Arrays.stream(permissions)
                .map(p -> store == null ? p.authority() : p.authority(store)).collect(Collectors.toSet());
        Actor actor = Actor.account(ACCOUNT, authorities);
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        token.setDetails(new TenantContext(tenant, MembershipKind.ACCOUNT, actor.userId(), "test:isolation"));
        return authentication(token);
    }
}
