package com.xavier.backend.catalog.service;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.CatalogFixtures.CATALOG;
import static com.xavier.backend.catalog.CatalogFixtures.code;
import static com.xavier.backend.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogFixtures;
import com.xavier.backend.catalog.CatalogModuleTest;
import com.xavier.backend.catalog.Exclusions;
import com.xavier.backend.catalog.Publications;
import com.xavier.backend.catalog.Publications.SellableStore;
import com.xavier.backend.catalog.repository.AutomaticItemRepository;
import com.xavier.backend.catalog.repository.BarcodeRepository;
import com.xavier.backend.catalog.repository.FamilyRepository;
import com.xavier.backend.catalog.repository.ItemPriceRepository;
import com.xavier.backend.catalog.repository.ItemRepository;
import com.xavier.backend.catalog.repository.ItemVariantRepository;
import com.xavier.backend.catalog.repository.MacroRepository;
import com.xavier.backend.catalog.repository.MenuGroupRepository;
import com.xavier.backend.catalog.repository.PriceListRepository;
import com.xavier.backend.catalog.repository.QuickPageRepository;
import com.xavier.backend.catalog.repository.TaxCategoryRepository;
import com.xavier.backend.catalog.repository.TaxRateRepository;
import com.xavier.backend.catalog.repository.UnitRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * D148: with no exclusion row for the store, a new snapshot is identical, field for field, to the one the R4 reader
 * ({@link R4WorkingCopy}, frozen verbatim) builds from the same working copy: items, variants, barcodes, a menu with
 * its groups, macros, the store's quick page and automatic item. Another store's exclusion of one of these items
 * changes nothing here.
 */
@CatalogModuleTest
class NoExclusionSnapshotUnchangedTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    WorkingCopy workingCopy;

    @Autowired
    ItemRepository items;
    @Autowired
    ItemVariantRepository variants;
    @Autowired
    BarcodeRepository barcodes;
    @Autowired
    FamilyRepository families;
    @Autowired
    UnitRepository units;
    @Autowired
    TaxCategoryRepository taxCategories;
    @Autowired
    TaxRateRepository taxRates;
    @Autowired
    PriceListRepository priceLists;
    @Autowired
    ItemPriceRepository prices;
    @Autowired
    MenuGroupRepository menuGroups;
    @Autowired
    MacroRepository macros;
    @Autowired
    QuickPageRepository quickPages;
    @Autowired
    AutomaticItemRepository automaticItems;

    final List<UUID> menus = new ArrayList<>();
    final List<UUID> madeMacros = new ArrayList<>();

    @AfterEach
    void removeCompanyWideData() {
        if (!madeMacros.isEmpty()) {
            jdbc.sql("DELETE FROM catalog.quick_page_entry WHERE macro_id IN (:ids)").param("ids", madeMacros).update();
            jdbc.sql("DELETE FROM catalog.macro WHERE id IN (:ids)").param("ids", madeMacros).update();
        }
        if (!menus.isEmpty()) {
            jdbc.sql("UPDATE catalog.item SET availability = 'INACTIVE', version = version + 1 WHERE id IN (:ids)")
                    .param("ids", menus).update();
        }
    }

    @Test
    void withNoExclusionRowsTheSnapshotEqualsTheR4One() throws Exception {
        CatalogFixtures fixtures = new CatalogFixtures(mvc);
        SellableStore sellable = new Publications(mvc, jdbc).sellableStore();
        UUID store = sellable.store();
        JsonNode drink = fixtures.item("STANDARD", "BAR");
        JsonNode large = fixtures.variant(id(drink));
        JsonNode menu = fixtures.item("MENU", null);
        menus.add(id(menu));
        for (JsonNode item : List.of(drink, menu)) {
            fixtures.price(store, sellable.priceList(), id(item), null, "5.00");
        }
        fixtures.price(store, sellable.priceList(), id(drink), id(large), "6.00");
        created(post(CATALOG + "/barcodes"), admin(Permission.CATALOG_MANAGE), """
                {"code": "%s", "itemId": "%s", "variantId": "%s"}""".formatted(code("560"), id(drink), id(large)));
        created(post(CATALOG + "/items/" + id(menu) + "/menu-groups"), admin(Permission.CATALOG_MANAGE), """
                {"code": "G", "name": "Drink", "minChoices": 1, "maxChoices": 1, "position": 0, "options": [
                  {"itemId": "%s", "variantId": "%s", "quantity": 1}, {"itemId": "%s", "quantity": 1}]}"""
                .formatted(id(drink), id(large), id(sellable.item())));
        JsonNode macro = created(post(CATALOG + "/macros"), admin(Permission.CATALOG_MANAGE), """
                {"code": "%s", "name": "Two", "entries": [{"itemId": "%s", "quantity": 2}]}"""
                .formatted(code("M"), id(drink)));
        madeMacros.add(id(macro));
        created(post(CATALOG + "/quick-pages"), inStore(store, Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "code": "MAIN", "name": "Main", "position": 0, "entries": [
                  {"itemId": "%s"}, {"variantId": "%s"}, {"macroId": "%s"}]}"""
                .formatted(store, id(menu), id(large), id(macro)));
        created(post(CATALOG + "/automatic-items"), inStore(store, Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "trigger": "TABLE_OPEN_PER_GUEST", "itemId": "%s", "quantity": 1}"""
                .formatted(store, id(drink)));
        UUID other = fixtures.store();
        new Exclusions(mvc).exclude(other, id(drink), "elsewhere").andExpect(status().isOk());

        Instant at = Instant.parse("2026-11-12T10:00:00Z");
        UUID releaseId = UUID.randomUUID();
        transactions.executeWithoutResult(tx -> {
            WorkingCopy.View view = workingCopy.of(store, at);
            assertThat(view.excluded()).isEmpty();
            assertThat(view.warnings()).isEmpty();
            String current = json.writeValueAsString(view.snapshot(releaseId, 7));
            String r4 = json.writeValueAsString(r4().of(store, at).snapshot(releaseId, 7));
            assertThat(current).contains(id(large).toString(), id(macro).toString(), "\"MAIN\"", "\"G\"")
                    .isEqualTo(r4);
        });
    }

    private R4WorkingCopy r4() {
        return new R4WorkingCopy(items, variants, barcodes, families, units, taxCategories, taxRates, priceLists,
                prices, menuGroups, macros, quickPages, automaticItems);
    }

    private JsonNode created(MockHttpServletRequestBuilder request, RequestPostProcessor actor, String body)
            throws Exception {
        return CatalogFixtures.json(mvc.perform(request.with(actor).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
}
