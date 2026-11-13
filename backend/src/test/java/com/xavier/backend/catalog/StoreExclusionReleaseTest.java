package com.xavier.backend.catalog;

import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.Publications.SellableStore;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * A62 (catalog part), D146: an item excluded from a store needs no price there and is left out of that store's next
 * release, which lists it under {@code excludedItems}; other stores are unaffected, and a forgotten price on an item
 * that is not excluded still fails with {@code ITEM_WITHOUT_PRICE}.
 */
@CatalogModuleTest
class StoreExclusionReleaseTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CatalogApi catalogApi;

    @Test
    void anItemExcludedFromAStoreNeedsNoPriceThereAndOnlyThatStoreLeavesItOut() throws Exception {
        SellableStore b = publications().sellableStore();
        SellableStore a = publications().sellableStore(); // after b: prices every item so far, b's too
        fixtures().price(b.store(), b.priceList(), id(a.item()), null, "3.00");
        JsonNode x = fixtures().item();
        fixtures().price(a.store(), a.priceList(), id(x), null, "7.00");

        validate(b.store())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.itemId == '%s')].code".formatted(id(x))).value("ITEM_WITHOUT_PRICE"))
                .andExpect(jsonPath("$.errors[?(@.itemId == '%s')].message".formatted(id(x)))
                        .value("Item " + x.get("code").asString() + " has no price in this store's price list: "
                                + "price it, or exclude it from this store"));

        exclusions().exclude(b.store(), id(x), "not sold here").andExpect(status().isOk());

        validate(b.store())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.excludedItems.length()").value(1))
                .andExpect(jsonPath("$.excludedItems[0].itemId").value(id(x).toString()))
                .andExpect(jsonPath("$.excludedItems[0].code").value(x.get("code").asString()))
                .andExpect(jsonPath("$.excludedItems[0].name").value(x.get("name").asString()))
                .andExpect(jsonPath("$.validationWarnings.length()").value(0));
        publications().publishNew(b.store());
        publications().publishNew(a.store());

        assertThat(itemIds(catalogApi.currentSnapshot(b.store()))).doesNotContain(id(x)).contains(id(b.item()));
        assertThat(itemIds(catalogApi.currentSnapshot(a.store()))).contains(id(x));
    }

    private static java.util.List<UUID> itemIds(CatalogSnapshot snapshot) {
        return snapshot.items().stream().map(CatalogSnapshot.Item::id).toList();
    }

    private ResultActions validate(UUID store) throws Exception {
        UUID release = id(publications().createDraft(store));
        return mvc.perform(post(Publications.RELEASES + "/" + release + "/validate")
                .with(inStore(store, Permission.CATALOG_MANAGE)).with(csrf()));
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
