package com.xavier.backend.catalog;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.CatalogFixtures.CATALOG;
import static com.xavier.backend.catalog.CatalogFixtures.code;
import static com.xavier.backend.catalog.CatalogFixtures.id;
import static com.xavier.backend.catalog.CatalogFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.Publications.SellableStore;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * D147, one test per row: what names an item that store B excludes. Company-wide or every-store configuration is
 * dropped from B's release with a warning (menu option, whole macro, quick page entry, automatic item); B's own quick
 * pages and automatic items naming it are errors. Company-wide and every-store data a test makes is removed after it,
 * because it would reach every other store's release.
 */
@CatalogModuleTest
class ExclusionReferenceRulesTest {

    static final String MACROS = CATALOG + "/macros";
    static final String QUICK_PAGES = CATALOG + "/quick-pages";
    static final String AUTOMATIC = CATALOG + "/automatic-items";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CatalogApi catalogApi;

    final List<UUID> menus = new ArrayList<>();
    final List<UUID> macros = new ArrayList<>();
    final List<UUID> everyStorePages = new ArrayList<>();
    final List<UUID> everyStoreAutomatic = new ArrayList<>();

    @AfterEach
    void removeSharedData() {
        if (!everyStorePages.isEmpty()) {
            jdbc.sql("DELETE FROM catalog.quick_page_entry WHERE page_id IN (:ids)").param("ids", everyStorePages)
                    .update();
            jdbc.sql("DELETE FROM catalog.quick_page WHERE id IN (:ids)").param("ids", everyStorePages).update();
        }
        if (!everyStoreAutomatic.isEmpty()) {
            jdbc.sql("DELETE FROM catalog.automatic_item WHERE id IN (:ids)").param("ids", everyStoreAutomatic)
                    .update();
        }
        if (!macros.isEmpty()) {
            jdbc.sql("DELETE FROM catalog.quick_page_entry WHERE macro_id IN (:ids)").param("ids", macros).update();
            jdbc.sql("DELETE FROM catalog.macro WHERE id IN (:ids)").param("ids", macros).update();
        }
        if (!menus.isEmpty()) {
            jdbc.sql("UPDATE catalog.item SET availability = 'INACTIVE', version = version + 1 WHERE id IN (:ids)")
                    .param("ids", menus).update();
        }
    }

    @Test
    void anExcludedMenuOptionIsDroppedFromTheStoresMenuWithAWarning() throws Exception {
        SellableStore b = publications().sellableStore();
        JsonNode x = fixtures().item();
        JsonNode y = fixtures().item();
        JsonNode menu = menu();
        priceAll(b, x, y, menu);
        menuGroup(menu, 1, 1, x, y);
        exclusions().exclude(b.store(), id(x), null).andExpect(status().isOk());

        UUID release = id(publications().createDraft(b.store()));
        JsonNode validated = json(validate(b.store(), release).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());

        assertThat(warnings(validated)).containsExactly(tuple("MENU_OPTION_EXCLUDED", id(x).toString()));
        mvc.perform(get(Publications.RELEASES + "/" + release).with(inStore(b.store(), Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validationWarnings[0].code").value("MENU_OPTION_EXCLUDED"))
                .andExpect(jsonPath("$.validationWarnings[0].itemId").value(id(x).toString()));
        publications().publish(b.store(), release, UUID.randomUUID().toString(), "{}").andExpect(status().isOk());
        CatalogSnapshot.Item frozen = catalogApi.currentSnapshot(b.store()).items().stream()
                .filter(item -> item.id().equals(id(menu))).findFirst().orElseThrow();
        assertThat(frozen.menuGroups()).singleElement()
                .satisfies(group -> assertThat(group.options()).extracting(CatalogSnapshot.MenuOption::itemId)
                        .containsExactly(id(y)));
    }

    @Test
    void aMenuGroupThatCanNoLongerMeetItsMinimumIsAnError() throws Exception {
        SellableStore b = publications().sellableStore();
        JsonNode x = fixtures().item();
        JsonNode y = fixtures().item();
        JsonNode menu = menu();
        priceAll(b, x, y, menu);
        menuGroup(menu, 2, 2, x, y);
        exclusions().exclude(b.store(), id(x), null).andExpect(status().isOk());

        validate(b.store(), id(publications().createDraft(b.store())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("MENU_GROUP_BOUNDS_INVALID"))
                .andExpect(jsonPath("$.errors[0].itemId").value(id(menu).toString()));
    }

    @Test
    void aMacroWithAnExcludedEntryIsOmittedWholeFromThatStoreOnly() throws Exception {
        SellableStore b = publications().sellableStore();
        JsonNode x = fixtures().item();
        JsonNode y = fixtures().item();
        priceAll(b, x, y);
        JsonNode macro = macro(x, y);
        JsonNode kept = macro(y);
        SellableStore a = publications().sellableStore(); // prices every item made so far
        priceAll(b, a.item());
        exclusions().exclude(b.store(), id(x), null).andExpect(status().isOk());

        UUID release = id(publications().createDraft(b.store()));
        assertThat(warnings(json(validate(b.store(), release).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()))).containsExactly(tuple("MACRO_OMITTED", id(x).toString()));
        publications().publish(b.store(), release, UUID.randomUUID().toString(), "{}").andExpect(status().isOk());
        publications().publishNew(a.store());

        assertThat(macroIds(catalogApi.currentSnapshot(b.store()))).contains(id(kept)).doesNotContain(id(macro));
        assertThat(macroIds(catalogApi.currentSnapshot(a.store()))).contains(id(kept), id(macro));
    }

    @Test
    void publishingRefreshesTheStoredWarningsAndOnlyValidationListsExcludedItems() throws Exception {
        SellableStore b = publications().sellableStore();
        JsonNode x = fixtures().item();
        JsonNode y = fixtures().item();
        priceAll(b, x, y);
        macro(x, y);
        JsonNode draft = publications().createDraft(b.store());
        assertThat(draft.get("validationWarnings").isNull()).as("not validated yet").isTrue();
        UUID release = id(draft);
        validate(b.store(), release).andExpect(status().isOk())
                .andExpect(jsonPath("$.excludedItems.length()").value(0))
                .andExpect(jsonPath("$.validationWarnings.length()").value(0));

        exclusions().exclude(b.store(), id(x), null).andExpect(status().isOk());
        publications().publish(b.store(), release, UUID.randomUUID().toString(), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.excludedItems").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.validationWarnings.length()").value(1))
                .andExpect(jsonPath("$.validationWarnings[0].code").value("MACRO_OMITTED"));

        mvc.perform(get(Publications.RELEASES + "/" + release).with(inStore(b.store(), Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excludedItems").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.validationWarnings[0].code").value("MACRO_OMITTED"))
                .andExpect(jsonPath("$.validationWarnings[0].itemId").value(id(x).toString()));
    }

    @Test
    void entriesOfAnEveryStoreQuickPageNamingAnExcludedItemOrAnOmittedMacroAreOmitted() throws Exception {
        SellableStore b = publications().sellableStore();
        JsonNode x = fixtures().item();
        JsonNode y = fixtures().item();
        JsonNode large = fixtures().variant(id(x));
        priceAll(b, x, y);
        JsonNode macro = macro(x, y);
        JsonNode page = created(post(QUICK_PAGES), admin(Permission.CATALOG_MANAGE), """
                {"code": "%s", "name": "Every store", "position": 9, "entries": [
                  {"itemId": "%s"}, {"variantId": "%s"}, {"macroId": "%s"}, {"itemId": "%s"}]}"""
                .formatted(code("P"), id(x), id(large), id(macro), id(y)));
        everyStorePages.add(id(page));
        exclusions().exclude(b.store(), id(x), null).andExpect(status().isOk());

        UUID release = id(publications().createDraft(b.store()));
        assertThat(warnings(json(validate(b.store(), release).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()))).containsExactly(tuple("MACRO_OMITTED", id(x).toString()),
                tuple("QUICK_PAGE_ENTRY_OMITTED", id(x).toString()),
                tuple("QUICK_PAGE_ENTRY_OMITTED", id(x).toString()),
                tuple("QUICK_PAGE_ENTRY_OMITTED", id(x).toString()));
        publications().publish(b.store(), release, UUID.randomUUID().toString(), "{}").andExpect(status().isOk());

        assertThat(catalogApi.currentSnapshot(b.store()).quickPages())
                .filteredOn(frozen -> frozen.id().equals(id(page))).singleElement()
                .satisfies(frozen -> assertThat(frozen.entries())
                        .containsExactly(new CatalogSnapshot.QuickPageEntry(id(y), null, null)));
    }

    @Test
    void aQuickPageOfTheStoreNamingAnExcludedItemOrAMacroWithOneIsAnError() throws Exception {
        SellableStore b = publications().sellableStore();
        JsonNode x = fixtures().item();
        JsonNode y = fixtures().item();
        priceAll(b, x, y);
        JsonNode macro = macro(x, y);
        created(post(QUICK_PAGES), inStore(b.store(), Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "code": "P", "name": "P", "position": 0, "entries": [
                  {"itemId": "%s"}, {"macroId": "%s"}, {"itemId": "%s"}]}"""
                .formatted(b.store(), id(x), id(macro), id(y)));
        exclusions().exclude(b.store(), id(x), null).andExpect(status().isOk());

        validate(b.store(), id(publications().createDraft(b.store())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].code").value("QUICK_PAGE_ENTRY_EXCLUDED"))
                .andExpect(jsonPath("$.errors[0].itemId").value(id(x).toString()))
                .andExpect(jsonPath("$.errors[1].code").value("QUICK_PAGE_ENTRY_EXCLUDED"))
                .andExpect(jsonPath("$.errors[1].itemId").value(id(x).toString()));
    }

    @Test
    void anEveryStoreAutomaticItemNamingAnExcludedItemIsOmitted() throws Exception {
        SellableStore b = publications().sellableStore();
        JsonNode x = fixtures().item();
        priceAll(b, x);
        JsonNode automatic = created(post(AUTOMATIC), admin(Permission.CATALOG_MANAGE), """
                {"trigger": "ORDER_OPEN", "mode": "DELIVERY", "itemId": "%s", "quantity": 1}""".formatted(id(x)));
        everyStoreAutomatic.add(id(automatic));
        exclusions().exclude(b.store(), id(x), null).andExpect(status().isOk());

        UUID release = id(publications().createDraft(b.store()));
        assertThat(warnings(json(validate(b.store(), release).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()))).containsExactly(tuple("AUTOMATIC_ITEM_OMITTED", id(x).toString()));
        publications().publish(b.store(), release, UUID.randomUUID().toString(), "{}").andExpect(status().isOk());

        assertThat(catalogApi.currentSnapshot(b.store()).automaticItems())
                .extracting(CatalogSnapshot.AutomaticItem::id).doesNotContain(id(automatic));
    }

    @Test
    void anAutomaticItemOfTheStoreNamingAnExcludedItemIsAnError() throws Exception {
        SellableStore b = publications().sellableStore();
        JsonNode x = fixtures().item();
        priceAll(b, x);
        created(post(AUTOMATIC), inStore(b.store(), Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "trigger": "ORDER_OPEN", "itemId": "%s", "quantity": 1}"""
                .formatted(b.store(), id(x)));
        exclusions().exclude(b.store(), id(x), null).andExpect(status().isOk());

        validate(b.store(), id(publications().createDraft(b.store())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("AUTOMATIC_ITEM_EXCLUDED"))
                .andExpect(jsonPath("$.errors[0].itemId").value(id(x).toString()));
    }

    private static List<org.assertj.core.groups.Tuple> warnings(JsonNode release) {
        return StreamSupport.stream(release.get("validationWarnings").spliterator(), false)
                .map(warning -> tuple(warning.get("code").asString(), warning.get("itemId").asString())).toList();
    }

    private static List<UUID> macroIds(CatalogSnapshot snapshot) {
        return snapshot.macros().stream().map(CatalogSnapshot.Macro::id).toList();
    }

    private JsonNode menu() throws Exception {
        JsonNode menu = fixtures().item("MENU", null);
        menus.add(id(menu));
        return menu;
    }

    private void menuGroup(JsonNode menu, int min, int max, JsonNode... options) throws Exception {
        String lines = String.join(",", java.util.Arrays.stream(options)
                .map(option -> "{\"itemId\": \"%s\", \"quantity\": 1}".formatted(id(option))).toList());
        created(post(CATALOG + "/items/" + id(menu) + "/menu-groups"), admin(Permission.CATALOG_MANAGE), """
                {"code": "G", "name": "Group", "minChoices": %d, "maxChoices": %d, "position": 0, "options": [%s]}"""
                .formatted(min, max, lines));
    }

    private JsonNode macro(JsonNode... entries) throws Exception {
        String lines = String.join(",", java.util.Arrays.stream(entries)
                .map(entry -> "{\"itemId\": \"%s\", \"quantity\": 1}".formatted(id(entry))).toList());
        JsonNode macro = created(post(MACROS), admin(Permission.CATALOG_MANAGE), """
                {"code": "%s", "name": "Macro", "entries": [%s]}""".formatted(code("M"), lines));
        macros.add(id(macro));
        return macro;
    }

    private JsonNode created(MockHttpServletRequestBuilder request, RequestPostProcessor actor, String body)
            throws Exception {
        return json(mvc.perform(request.with(actor).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions validate(UUID store, UUID release) throws Exception {
        return mvc.perform(post(Publications.RELEASES + "/" + release + "/validate")
                .with(inStore(store, Permission.CATALOG_MANAGE)).with(csrf()));
    }

    private void priceAll(SellableStore sellable, JsonNode... items) throws Exception {
        for (JsonNode item : items) {
            fixtures().price(sellable.store(), sellable.priceList(), id(item), null, "5.00");
        }
    }

    private Exclusions exclusions() {
        return new Exclusions(mvc);
    }

    private CatalogFixtures fixtures() {
        return new CatalogFixtures(mvc);
    }

    private Publications publications() {
        return new Publications(mvc, jdbc);
    }
}
