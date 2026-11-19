package com.xavier.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.TestAuthentications;
import com.xavier.backend.access.Permission;
import com.xavier.backend.architecture.CompositeForeignKeyCoverageTest;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
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
 * A80, A82 and A84 (catalog): tenants A and B with the same unit, family, tax, item, barcode and variant codes. Through
 * the API, B's item and price list addressed by id from A are absent (404), and another store's price list of A is
 * forbidden (403, D42). With no {@code AccessPolicy} in front (the application-role seam), B's rows are invisible to
 * A, a row with B's {@code tenant_id} is refused ({@code 42501}), a row never moves tenant ({@code RS002}), a child
 * cannot reference B's parent (composite foreign key), and an unbound read raises {@code RS003}. Strict: no
 * bootstrap-tenant fallback.
 */
@CatalogModuleTest
@StrictTenantScope
class CatalogTenantIsolationTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    static final UUID A_STORE = UUID.fromString("0190b500-0000-7000-8000-0000000000a1");
    static final UUID A_OTHER_STORE = UUID.fromString("0190b500-0000-7000-8000-0000000000a2");
    static final UUID B_STORE = UUID.fromString("0190b500-0000-7000-8000-0000000000b1");

    static final UUID A_ITEM = UUID.fromString("0190b500-0000-7000-8000-0000000001a1");
    static final UUID B_ITEM = UUID.fromString("0190b500-0000-7000-8000-0000000001b1");
    static final UUID A_FAMILY = UUID.fromString("0190b500-0000-7000-8000-0000000002a1");
    static final UUID B_FAMILY = UUID.fromString("0190b500-0000-7000-8000-0000000002b1");
    static final UUID A_LIST = UUID.fromString("0190b500-0000-7000-8000-0000000003a1");
    static final UUID A_OTHER_LIST = UUID.fromString("0190b500-0000-7000-8000-0000000003a2");
    static final UUID B_LIST = UUID.fromString("0190b500-0000-7000-8000-0000000003b1");

    static final List<String> TABLES = List.of("unit", "family", "tax_category", "item", "item_variant", "barcode",
            "price_list", "item_price", "quick_page", "quick_page_entry");

    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void twoTenantsWithIdenticalCodes() {
        TenantTestSupport.ensureTenants();
        seed(A, A_STORE, A_ITEM, A_FAMILY, A_LIST);
        seed(B, B_STORE, B_ITEM, B_FAMILY, B_LIST);
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            app.jdbc().sql("INSERT INTO catalog.price_list (id, store_id, name, currency, version, created_at, "
                    + "updated_at) VALUES (?, ?, 'Other', 'MZN', 0, now(), now())")
                    .param(A_OTHER_LIST).param(A_OTHER_STORE).update();
        }
    }

    /** As the application role in {@code tenant}: no tenant_id is written, the column default takes the bound one. */
    private static void seed(UUID tenant, UUID store, UUID item, UUID family, UUID list) {
        UUID unit = UUID.randomUUID();
        UUID tax = UUID.randomUUID();
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("INSERT INTO catalog.unit (id, code, name, version, created_at, updated_at) "
                    + "VALUES (?, 'ISO-U', 'Piece', 0, now(), now())").param(unit).update();
            app.jdbc().sql("INSERT INTO catalog.family (id, code, name, version, created_at, updated_at) "
                    + "VALUES (?, 'ISO-F', 'Drinks', 0, now(), now())").param(family).update();
            app.jdbc().sql("INSERT INTO catalog.tax_category (id, code, name, version, created_at, updated_at) "
                    + "VALUES (?, 'ISO-T', 'VAT', 0, now(), now())").param(tax).update();
            app.jdbc().sql("INSERT INTO catalog.item (id, code, name, family_id, unit_id, tax_category_id, "
                    + "availability, sold_by_weight, version, created_at, updated_at, kind) VALUES (?, 'ISO-ITEM', "
                    + "'Cola', ?, ?, ?, 'ACTIVE', false, 0, now(), now(), 'STANDARD')")
                    .param(item).param(family).param(unit).param(tax).update();
            UUID variant = UUID.randomUUID();
            app.jdbc().sql("INSERT INTO catalog.item_variant (id, item_id, code, name, version, created_at, "
                    + "updated_at) VALUES (?, ?, 'LARGE', 'Large', 0, now(), now())").param(variant).param(item)
                    .update();
            app.jdbc().sql("INSERT INTO catalog.barcode (id, code, item_id, version, created_at, updated_at) "
                    + "VALUES (?, '5601234567890', ?, 0, now(), now())").param(UUID.randomUUID()).param(item).update();
            app.jdbc().sql("INSERT INTO catalog.price_list (id, store_id, name, currency, version, created_at, "
                    + "updated_at) VALUES (?, ?, 'Main', 'MZN', 0, now(), now())").param(list).param(store).update();
            app.jdbc().sql("INSERT INTO catalog.item_price (id, price_list_id, item_id, amount, currency, version, "
                    + "created_at, updated_at) VALUES (?, ?, ?, 9.00, 'MZN', 0, now(), now())")
                    .param(UUID.randomUUID()).param(list).param(item).update();
        }
    }

    @Test
    void bothTenantsUseTheSameItemCodeAndBarcode() {
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT i.tenant_id FROM catalog.item i "
                        + "JOIN catalog.barcode b ON b.item_id = i.id AND b.code = '5601234567890' "
                        + "WHERE i.code = 'ISO-ITEM'").query(UUID.class).list())
                .containsExactlyInAnyOrder(A, B);
    }

    @Test
    void aTenantSeesOnlyItsOwnRows() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThat(app.jdbc().sql("SELECT id FROM catalog.item WHERE code = 'ISO-ITEM'").query(UUID.class).list())
                    .containsExactly(A_ITEM);
            assertThat(app.jdbc().sql("SELECT count(*) FROM catalog.barcode WHERE code = '5601234567890'")
                    .query(Long.class).single()).isOne();
            assertThat(app.jdbc().sql("SELECT count(*) FROM catalog.item_price WHERE price_list_id = ?")
                    .param(B_LIST).query(Long.class).single()).isZero();
            assertThat(app.jdbc().sql("SELECT id FROM catalog.price_list WHERE name IN ('Main', 'Other')")
                    .query(UUID.class).list()).containsExactlyInAnyOrder(A_LIST, A_OTHER_LIST);
        }
    }

    @Test
    void anotherTenantsItemById404AndOwnItem200() throws Exception {
        mvc.perform(get("/api/v1/backoffice/catalog/items/" + B_ITEM).with(in(A, null, Permission.CATALOG_MANAGE)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/backoffice/catalog/items/" + A_ITEM).with(in(A, null, Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/backoffice/catalog/items").with(in(A, null, Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk());
    }

    @Test
    void anotherTenantsPriceList404AndAnotherStoreOfTheTenant403() throws Exception {
        RequestPostProcessor managerOfAStore = in(A, A_STORE, Permission.CATALOG_MANAGE);

        mvc.perform(get("/api/v1/backoffice/catalog/price-lists/" + B_LIST).with(managerOfAStore))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/backoffice/catalog/price-lists/" + A_OTHER_LIST).with(managerOfAStore))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/backoffice/catalog/price-lists/" + A_LIST).with(managerOfAStore))
                .andExpect(status().isOk());
    }

    @Test
    void aRowWithAnotherTenantsIdIsRefused() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("INSERT INTO catalog.unit (id, tenant_id, code, name, "
                    + "version, created_at, updated_at) VALUES (?, ?, 'ISO-X', 'X', 0, now(), now())")
                    .param(UUID.randomUUID()).param(B).update(), StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        }
    }

    @Test
    void aRowNeverMovesToAnotherTenant() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE catalog.item SET tenant_id = ? WHERE id = ?")
                    .param(B).param(A_ITEM).update(), StoreScopeViolations.Kind.STORE_CHANGE);
        }
    }

    @Test
    void aChildCannotReferenceAnotherTenantsParent() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThatThrownBy(() -> app.jdbc().sql("INSERT INTO catalog.item (id, code, name, family_id, unit_id, "
                    + "tax_category_id, availability, sold_by_weight, version, created_at, updated_at, kind) "
                    + "SELECT ?, 'ISO-FORGED', 'Forged', ?, unit_id, tax_category_id, 'ACTIVE', false, 0, now(), "
                    + "now(), 'STANDARD' FROM catalog.item WHERE id = ?")
                    .param(UUID.randomUUID()).param(B_FAMILY).param(A_ITEM).update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
        }
    }

    @Test
    void anUnboundReadFailsClosed() {
        try (AppRoleSession app = AppRoleSession.open().noTenant().allStores()) {
            for (String table : TABLES) {
                AppRoleSession.assertRefused(() -> app.jdbc().sql("SELECT count(*) FROM catalog." + table)
                        .query(Long.class).single(), StoreScopeViolations.Kind.TENANT_MISSING);
            }
        }
    }

    /** FE-F35: the code columns of the working copy (barcodes excepted: exact match) are unique ignoring case. */
    static final List<String> CODE_KEYS = List.of("family_code_uk", "unit_code_uk", "tax_category_code_uk",
            "item_code_uk", "item_variant_item_code_uk", "menu_group_item_code_uk", "macro_code_uk",
            "quick_page_store_code_uk");

    @Test
    void everyUniqueKeyOnACodeComparesTheCodeIgnoringCaseExceptBarcodes() {
        List<String> definitions = OwnerDatabase.shared().jdbc().sql("SELECT indexname || ' ' || indexdef "
                        + "FROM pg_indexes WHERE schemaname = 'catalog' AND indexdef LIKE 'CREATE UNIQUE INDEX%' "
                        + "AND indexdef ~ '[(, ]code[),]' AND indexname <> 'barcode_code_uk'")
                .query(String.class).list();

        assertThat(definitions).hasSize(CODE_KEYS.size()).allSatisfy(definition -> assertThat(definition)
                .contains("(tenant_id, ").contains("upper((code)::text)"));
        assertThat(definitions).extracting(definition -> definition.substring(0, definition.indexOf(' ')))
                .containsExactlyInAnyOrderElementsOf(CODE_KEYS);
        assertThat(definitions).filteredOn(definition -> definition.startsWith("quick_page_store_code_uk"))
                .singleElement().asString().contains("NULLS NOT DISTINCT");
    }

    @Test
    void aCaseVariantOfACodeInTheSameScopeIsRefusedByTheDatabase() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertCaseVariantRefused(() -> app.jdbc().sql("INSERT INTO catalog.unit (id, code, name, version, "
                    + "created_at, updated_at) VALUES (?, 'iso-u', 'Piece', 0, now(), now())")
                    .param(UUID.randomUUID()).update(), "unit_code_uk");
            assertCaseVariantRefused(() -> app.jdbc().sql("INSERT INTO catalog.item_variant (id, item_id, code, "
                    + "name, version, created_at, updated_at) VALUES (?, ?, 'Large', 'Large', 0, now(), now())")
                    .param(UUID.randomUUID()).param(A_ITEM).update(), "item_variant_item_code_uk");
        }
    }

    private static void assertCaseVariantRefused(Runnable insert, String key) {
        assertThatThrownBy(insert::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23505"))
                .hasMessageContaining(key);
    }

    @Test
    void theModuleIsTenantProtected() {
        RowLevelSecurityCoverageTest.assertModuleTenantProtected("catalog");
        CompositeForeignKeyCoverageTest.assertModuleKeysIncludeTheTenant("catalog");
    }

    /** An account of {@code tenant} (its resolved membership as the principal's details), in a store or everywhere. */
    private static RequestPostProcessor in(UUID tenant, UUID store, Permission permission) {
        String authority = store == null ? permission.authority() : permission.authority(store);
        Actor actor = Actor.account(UUID.randomUUID(), Set.of(authority));
        return TestAuthentications.as(actor, tenant);
    }
}
