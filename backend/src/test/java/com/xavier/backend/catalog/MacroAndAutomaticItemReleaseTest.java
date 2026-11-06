package com.xavier.backend.catalog;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.CatalogFixtures.CATALOG;
import static com.xavier.backend.catalog.CatalogFixtures.code;
import static com.xavier.backend.catalog.CatalogFixtures.id;
import static com.xavier.backend.catalog.CatalogFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.Publications.SellableStore;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * D109: quick pages, macros and automatic items compose lines, so they are in the release: validated against it and
 * frozen into the v2 snapshot, which later edits never change. Quick pages and automatic items are per store or for
 * every store; macros are company-wide (a test that leaves an invalid macro deletes it when done).
 */
@CatalogModuleTest
class MacroAndAutomaticItemReleaseTest {

    static final String MACROS = CATALOG + "/macros";
    static final String QUICK_PAGES = CATALOG + "/quick-pages";
    static final String AUTOMATIC = CATALOG + "/automatic-items";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CatalogApi catalogApi;

    @Test
    void aReleaseFreezesTheMacrosQuickPagesAndActiveAutomaticItemsTheStoreSees() throws Exception {
        SellableStore sellable = publications().sellableStore();
        UUID other = new CatalogFixtures(mvc).store();
        JsonNode cover = fixtures().item("STANDARD", null);
        JsonNode drink = fixtures().item("STANDARD", "BAR");
        JsonNode large = fixtures().variant(id(drink));
        priceAll(sellable, cover, drink);
        JsonNode macro = created(post(MACROS), admin(Permission.CATALOG_MANAGE), """
                {"code": "%s", "name": "Two drinks", "entries": [
                  {"itemId": "%s", "variantId": "%s", "quantity": 2}, {"itemId": "%s", "quantity": 1}]}"""
                .formatted(code("M"), id(drink), id(large), id(cover)));
        JsonNode page = created(post(QUICK_PAGES), inStore(sellable.store(), Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "code": "MAIN", "name": "Main", "position": 0, "entries": [
                  {"itemId": "%s"}, {"variantId": "%s"}, {"macroId": "%s"}]}"""
                .formatted(sellable.store(), id(cover), id(large), id(macro)));
        created(post(QUICK_PAGES), inStore(other, Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "code": "MAIN", "name": "Elsewhere", "position": 0, "entries": []}""".formatted(other));
        JsonNode perGuest = created(post(AUTOMATIC), inStore(sellable.store(), Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "trigger": "TABLE_OPEN_PER_GUEST", "itemId": "%s", "quantity": 1}"""
                .formatted(sellable.store(), id(cover)));
        JsonNode takeaway = created(post(AUTOMATIC), inStore(sellable.store(), Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "trigger": "ORDER_OPEN", "mode": "TAKEAWAY", "itemId": "%s", "quantity": 1}"""
                .formatted(sellable.store(), id(drink)));
        created(post(AUTOMATIC), inStore(sellable.store(), Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "trigger": "ORDER_OPEN", "itemId": "%s", "quantity": 1, "active": false}"""
                .formatted(sellable.store(), id(drink)));

        publications().publishNew(sellable.store());
        CatalogSnapshot snapshot = catalogApi.currentSnapshot(sellable.store());

        assertThat(snapshot.macros()).filteredOn(frozen -> frozen.id().equals(id(macro))).singleElement()
                .satisfies(frozen -> assertThat(frozen.entries()).containsExactly(
                        new CatalogSnapshot.MacroEntry(id(drink), id(large), new BigDecimal("2.000")),
                        new CatalogSnapshot.MacroEntry(id(cover), null, new BigDecimal("1.000"))));
        assertThat(snapshot.quickPages()).filteredOn(frozen -> frozen.code().equals("MAIN")).singleElement()
                .satisfies(frozen -> {
                    assertThat(frozen.id()).isEqualTo(id(page));
                    assertThat(frozen.entries()).containsExactly(
                            new CatalogSnapshot.QuickPageEntry(id(cover), null, null),
                            new CatalogSnapshot.QuickPageEntry(null, id(large), null),
                            new CatalogSnapshot.QuickPageEntry(null, null, id(macro)));
                });
        assertThat(snapshot.automaticItems()).filteredOn(frozen -> frozen.itemId().equals(id(cover))
                        || frozen.itemId().equals(id(drink)))
                .containsExactly(
                        new CatalogSnapshot.AutomaticItem(id(perGuest), "TABLE_OPEN_PER_GUEST", null, id(cover), null,
                                new BigDecimal("1.000")),
                        new CatalogSnapshot.AutomaticItem(id(takeaway), "ORDER_OPEN", "TAKEAWAY", id(drink), null,
                                new BigDecimal("1.000"))); // the inactive one is not frozen
    }

    @Test
    void laterEditsNeverChangeAFrozenRelease() throws Exception {
        SellableStore sellable = publications().sellableStore();
        JsonNode cover = fixtures().item("STANDARD", null);
        priceAll(sellable, cover);
        JsonNode automatic = created(post(AUTOMATIC), inStore(sellable.store(), Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "trigger": "TABLE_OPEN_PER_GUEST", "itemId": "%s", "quantity": 1}"""
                .formatted(sellable.store(), id(cover)));
        UUID release = publications().publishNew(sellable.store());
        String frozen = snapshotText(release);

        mvc.perform(put(AUTOMATIC + "/" + id(automatic)).with(inStore(sellable.store(), Permission.CATALOG_MANAGE))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                                {"trigger": "TABLE_OPEN_PER_GUEST", "itemId": "%s", "quantity": 2, "active": true,
                                 "version": 0}""".formatted(id(cover))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(2));
        UUID next = publications().publishNew(sellable.store());

        assertThat(snapshotText(release)).isEqualTo(frozen);
        assertThat(catalogApi.snapshot(sellable.store(), release).automaticItems()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualByComparingTo("1"));
        assertThat(catalogApi.snapshot(sellable.store(), next).automaticItems()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualByComparingTo("2"));
    }

    @Test
    void validationRefusesAnAutomaticItemOrQuickPageNamingAnItemOutsideTheRelease() throws Exception {
        SellableStore sellable = publications().sellableStore();
        JsonNode retired = fixtures().item("STANDARD", null);
        JsonNode retiredForPage = fixtures().item("STANDARD", null);
        created(post(AUTOMATIC), inStore(sellable.store(), Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "trigger": "ORDER_OPEN", "itemId": "%s", "quantity": 1}"""
                .formatted(sellable.store(), id(retired)));
        created(post(QUICK_PAGES), inStore(sellable.store(), Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "code": "P", "name": "P", "position": 1, "entries": [{"itemId": "%s"}]}"""
                .formatted(sellable.store(), id(retiredForPage)));
        fixtures().deactivate(retired);
        fixtures().deactivate(retiredForPage);

        validate(sellable.store())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.itemId == '%s')].code".formatted(id(retired)))
                        .value("AUTOMATIC_ITEM_NOT_IN_RELEASE"))
                .andExpect(jsonPath("$.errors[?(@.itemId == '%s')].code".formatted(id(retiredForPage)))
                        .value("QUICK_PAGE_ENTRY_NOT_IN_RELEASE"));
        UUID unaffected = publications().sellableStore().store(); // store-scoped: another store still publishes
        publications().publishNew(unaffected);
    }

    @Test
    void validationRefusesAMacroNamingAnItemOutsideTheRelease() throws Exception {
        SellableStore sellable = publications().sellableStore();
        JsonNode retired = fixtures().item("STANDARD", null);
        JsonNode macro = created(post(MACROS), admin(Permission.CATALOG_MANAGE), """
                {"code": "%s", "name": "Old", "entries": [{"itemId": "%s", "quantity": 1}]}"""
                .formatted(code("M"), id(retired)));
        try {
            fixtures().deactivate(retired);

            validate(sellable.store())
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.itemId == '%s')].code".formatted(id(retired)))
                            .value("MACRO_ENTRY_NOT_IN_RELEASE"));
        } finally {
            mvc.perform(delete(MACROS + "/" + id(macro)).param("version", "0").with(admin(Permission.CATALOG_MANAGE))
                    .with(csrf())).andExpect(status().isNoContent());
        }
    }

    @Test
    void entriesAreCheckedWhenWrittenAndScopedByStore() throws Exception {
        UUID store = new CatalogFixtures(mvc).store();
        JsonNode item = fixtures().item("STANDARD", null);
        JsonNode otherVariant = fixtures().variant(id(fixtures().item("STANDARD", null)));

        perform(post(MACROS), admin(Permission.CATALOG_MANAGE), """
                {"code": "%s", "name": "X", "entries": [{"itemId": "%s", "variantId": "%s", "quantity": 1}]}"""
                .formatted(code("M"), id(item), id(otherVariant))).andExpect(status().isUnprocessableContent());
        perform(post(QUICK_PAGES), inStore(store, Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "code": "P", "name": "P", "position": 0,
                 "entries": [{"itemId": "%s", "macroId": "%s"}]}""".formatted(store, id(item), UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent());
        perform(post(AUTOMATIC), inStore(store, Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "trigger": "TABLE_OPEN_PER_GUEST", "mode": "DINE_IN", "itemId": "%s",
                 "quantity": 1}""".formatted(store, id(item))).andExpect(status().isUnprocessableContent());
        perform(post(AUTOMATIC), inStore(store, Permission.CATALOG_MANAGE), """
                {"trigger": "ORDER_OPEN", "itemId": "%s", "quantity": 1}""".formatted(id(item)))
                .andExpect(status().isForbidden()); // every store needs an installation-wide grant
        JsonNode page = created(post(QUICK_PAGES), inStore(store, Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "code": "P", "name": "P", "position": 0, "entries": []}""".formatted(store));
        UUID otherStore = new CatalogFixtures(mvc).store();
        mvc.perform(get(QUICK_PAGES + "/" + id(page)).with(inStore(otherStore, Permission.CATALOG_MANAGE)))
                .andExpect(status().isForbidden());
        mvc.perform(get(QUICK_PAGES + "/" + UUID.randomUUID()).with(inStore(otherStore, Permission.CATALOG_MANAGE)))
                .andExpect(status().isNotFound());
        mvc.perform(get(QUICK_PAGES).param("storeId", store.toString())
                        .with(inStore(store, Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].code".formatted(id(page))).value("P"));
        perform(post(QUICK_PAGES), inStore(store, Permission.CATALOG_MANAGE), """
                {"storeId": "%s", "code": "P", "name": "Again", "position": 0, "entries": []}""".formatted(store))
                .andExpect(status().isConflict());
    }

    private JsonNode created(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                             RequestPostProcessor actor, String body) throws Exception {
        return json(perform(request, actor, body).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString());
    }

    private ResultActions perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                  RequestPostProcessor actor, String body) throws Exception {
        return mvc.perform(request.with(actor).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions validate(UUID store) throws Exception {
        UUID release = id(publications().createDraft(store));
        return mvc.perform(post(Publications.RELEASES + "/" + release + "/validate")
                .with(inStore(store, Permission.CATALOG_MANAGE)).with(csrf()));
    }

    private void priceAll(SellableStore sellable, JsonNode... items) throws Exception {
        for (JsonNode item : items) {
            fixtures().price(sellable.store(), sellable.priceList(), id(item), null, "5.00");
        }
    }

    private String snapshotText(UUID release) {
        return jdbc.sql("SELECT snapshot::text FROM catalog.configuration_release WHERE id = :id")
                .param("id", release).query(String.class).single();
    }

    private CatalogFixtures fixtures() {
        return new CatalogFixtures(mvc);
    }

    private Publications publications() {
        return new Publications(mvc, jdbc);
    }
}
