package com.xavier.backend.catalog.controller;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.Actors.pos;
import static com.xavier.backend.catalog.CatalogFixtures.CATALOG;
import static com.xavier.backend.catalog.CatalogFixtures.id;
import static com.xavier.backend.catalog.CatalogFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogFixtures;
import com.xavier.backend.catalog.CatalogModuleTest;
import com.xavier.backend.catalog.Publications;
import com.xavier.backend.catalog.Publications.SellableStore;
import com.xavier.backend.catalog.Race;
import com.xavier.backend.catalog.model.event.CatalogPublished;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.AssertablePublishedEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

@CatalogModuleTest
class ReleaseHttpTest {

    static final String RELEASES = Publications.RELEASES;
    static final String POS_CATALOG = "/api/v1/pos/catalog";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aStoreCatalogManagerCreatesADraftReleaseForTheStore() throws Exception {
        UUID store = fixtures().store();

        JsonNode draft = createDraft(store);

        assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.get("storeId").asString()).isEqualTo(store.toString());
        assertThat(draft.get("version").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'CATALOG_RELEASE_CREATED'"
                        + " AND entity_id = :id AND store_id = :store")
                .param("id", id(draft).toString()).param("store", store).query(Long.class).single()).isEqualTo(1);
        mvc.perform(get(RELEASES + "/" + id(draft)).with(inStore(store, Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mvc.perform(get(RELEASES).param("storeId", store.toString()).param("size", "10")
                        .with(inStore(store, Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id(draft).toString()))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void aDraftForAnUnknownStoreBreaksARuleAndAnotherStoresManagerIsRefused() throws Exception {
        UUID store = fixtures().store();
        UUID other = fixtures().store();

        mvc.perform(post(RELEASES).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\": \"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post(RELEASES).with(inStore(other, Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\": \"%s\"}".formatted(store)))
                .andExpect(status().isForbidden());
    }

    @Test
    void validationListsEveryActiveItemWithoutAPriceOrATaxRateInEffect() throws Exception {
        SellableStore sellable = sellableStore();
        JsonNode unpriced = fixtures().item();
        JsonNode untaxed = fixtures().item(fixtures().family(), fixtures().unit(), untaxedCategory());
        try {
            fixtures().price(sellable.store(), sellable.priceList(), id(untaxed), null, "3.00");
            UUID release = id(createDraft(sellable.store()));

            mvc.perform(post(RELEASES + "/" + release + "/validate")
                            .with(inStore(sellable.store(), Permission.CATALOG_MANAGE)).with(csrf()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.itemId == '%s')].code".formatted(id(unpriced)))
                            .value("ITEM_WITHOUT_PRICE"))
                    .andExpect(jsonPath("$.errors[?(@.itemId == '%s')].code".formatted(id(untaxed)))
                            .value("ITEM_WITHOUT_TAX_RATE"))
                    .andExpect(jsonPath("$.errors[?(@.itemId == '%s')].itemCode".formatted(id(unpriced)))
                            .value(unpriced.get("code").asString()))
                    .andExpect(jsonPath("$.errors[?(@.itemId == '%s')]".formatted(id(sellable.item()))).isEmpty());
            mvc.perform(get(RELEASES + "/" + release).with(inStore(sellable.store(), Permission.CATALOG_MANAGE)))
                    .andExpect(jsonPath("$.status").value("DRAFT"));
        } finally {
            deactivate(untaxed); // company-wide: an active item without a rate would block every later publication
        }
    }

    @Test
    void aStoreWithoutAPriceListCannotBeValidated() throws Exception {
        UUID store = fixtures().store();
        UUID release = id(createDraft(store));

        mvc.perform(post(RELEASES + "/" + release + "/validate").with(inStore(store, Permission.CATALOG_MANAGE))
                        .with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("STORE_WITHOUT_PRICE_LIST"));
    }

    @Test
    void publishingADraftIsAnIllegalTransition() throws Exception {
        SellableStore sellable = sellableStore();
        UUID release = id(createDraft(sellable.store()));

        publish(sellable.store(), release, UUID.randomUUID().toString(), "{}")
                .andExpect(status().isConflict());
    }

    @Test
    void publishingNeedsAnIdempotencyKeyAndCatalogPublishInThatStore() throws Exception {
        SellableStore sellable = sellableStore();
        UUID other = fixtures().store();
        UUID release = validatedRelease(sellable.store());

        mvc.perform(post(RELEASES + "/" + release + "/publish").with(inStore(sellable.store(), Permission.CATALOG_PUBLISH))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(RELEASES + "/" + release + "/publish")
                        .with(inStore(sellable.store(), Permission.CATALOG_MANAGE)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(RELEASES + "/" + release + "/publish").with(inStore(other, Permission.CATALOG_PUBLISH))
                        .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(RELEASES + "/" + release).with(inStore(sellable.store(), Permission.CATALOG_MANAGE)))
                .andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    @Test
    void publishFreezesTheCatalogAuditsTheAuthorAndAnnouncesIt(AssertablePublishedEvents events) throws Exception {
        SellableStore sellable = sellableStore();
        UUID release = validatedRelease(sellable.store());

        JsonNode published = json(publish(sellable.store(), release, UUID.randomUUID().toString(), "{\"note\": \"first\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(published.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(published.get("version").asLong()).isEqualTo(1);
        assertThat(published.get("publishedBy").asString()).isEqualTo(com.xavier.backend.catalog.Actors.USER_ID.toString());
        assertThat(published.get("effectiveAt").isNull()).isFalse();
        assertThat(events.ofType(CatalogPublished.class).matching(event -> event.releaseId().equals(release)
                && event.storeId().equals(sellable.store()) && event.version() == 1
                && event.effectiveAt().toString().equals(published.get("effectiveAt").asString()))).hasSize(1);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event WHERE action = 'CATALOG_RELEASE_PUBLISHED'
                        AND entity_id = :id AND store_id = :store AND actor_id = :actor AND details ->> 'version' = '1'
                        AND details ->> 'note' = 'first'""")
                .param("id", release.toString()).param("store", sellable.store())
                .param("actor", com.xavier.backend.catalog.Actors.USER_ID).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aNewPublicationRetiresTheOldOneWhoseSnapshotNeverChanges() throws Exception {
        // criterion §7.6, §14 #7 groundwork
        SellableStore sellable = sellableStore();
        UUID v1 = validatedRelease(sellable.store());
        publish(sellable.store(), v1, UUID.randomUUID().toString(), "{}").andExpect(status().isOk());
        String v1Snapshot = snapshotText(v1);

        changePrice(sellable, "99.90");
        UUID v2 = validatedRelease(sellable.store());
        publish(sellable.store(), v2, UUID.randomUUID().toString(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get(POS_CATALOG).with(pos(sellable.store(), Permission.CATALOG_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(v2.toString()))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.catalog.items[?(@.id == '%s')].price.amount".formatted(id(sellable.item())))
                        .value("99.90"));
        mvc.perform(get(RELEASES + "/" + v1).with(inStore(sellable.store(), Permission.CATALOG_MANAGE)))
                .andExpect(jsonPath("$.status").value("RETIRED"))
                .andExpect(jsonPath("$.retiredAt").isNotEmpty());
        assertThat(snapshotText(v1)).isEqualTo(v1Snapshot);
        assertThat(itemIn(json(v1Snapshot), id(sellable.item())).get("price").get("amount").asString())
                .isEqualTo("12.50");
    }

    @Test
    void editingTheWorkingCopyAfterPublishingDoesNotChangeThePublishedCatalog() throws Exception {
        SellableStore sellable = sellableStore();
        UUID release = validatedRelease(sellable.store());
        publish(sellable.store(), release, UUID.randomUUID().toString(), "{}").andExpect(status().isOk());
        String before = posCatalog(sellable.store());

        changePrice(sellable, "1.00");
        JsonNode item = sellable.item();
        mvc.perform(put(CATALOG + "/items/" + id(item)).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"code": "%s", "name": "Renamed", "familyId": "%s", "unitId": "%s", "taxCategoryId": "%s",
                                 "availability": "INACTIVE", "soldByWeight": false, "version": 0}"""
                                .formatted(item.get("code").asString(), item.get("familyId").asString(),
                                        item.get("unitId").asString(), item.get("taxCategoryId").asString())))
                .andExpect(status().isOk());

        assertThat(posCatalog(sellable.store())).isEqualTo(before);
    }

    @Test
    void repeatingAPublishWithTheSameKeyAndBodyReplaysOnePublication() throws Exception {
        // criterion §7.7
        SellableStore sellable = sellableStore();
        UUID release = validatedRelease(sellable.store());
        String key = UUID.randomUUID().toString();

        String first = publish(sellable.store(), release, key, "{\"note\": \"lunch menu\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replay = publish(sellable.store(), release, key, "{\"note\": \"lunch menu\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(json(replay)).isEqualTo(json(first));
        assertThat(publications(release)).isEqualTo(1);
        publish(sellable.store(), release, key, "{\"note\": \"dinner menu\"}")
                .andExpect(status().isUnprocessableContent());
        UUID otherRelease = validatedRelease(sellable.store());
        publish(sellable.store(), otherRelease, key, "{\"note\": \"lunch menu\"}")
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get(RELEASES + "/" + otherRelease).with(inStore(sellable.store(), Permission.CATALOG_MANAGE)))
                .andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    @Test
    void twoValidatedReleasesPublishedAtOnceLeaveExactlyOnePublished() throws Exception {
        for (int round = 0; round < 3; round++) {
            SellableStore sellable = sellableStore();
            UUID first = validatedRelease(sellable.store());
            UUID second = validatedRelease(sellable.store());

            List<Integer> statuses = Race.run(List.of(
                    () -> publish(sellable.store(), first, UUID.randomUUID().toString(), "{}")
                            .andReturn().getResponse().getStatus(),
                    () -> publish(sellable.store(), second, UUID.randomUUID().toString(), "{}")
                            .andReturn().getResponse().getStatus()));

            assertThat(statuses).as("round %d", round).contains(200).allMatch(s -> s == 200 || s == 409);
            assertThat(jdbc.sql("SELECT count(*) FROM catalog.configuration_release WHERE store_id = :store"
                            + " AND status = 'PUBLISHED'").param("store", sellable.store()).query(Long.class).single())
                    .isEqualTo(1);
        }
    }

    @Test
    void racingTwoValidatedReleasesAfterAFirstPublicationLeavesOnePublishedAndRetiresTheFirst() throws Exception {
        for (int round = 0; round < 3; round++) {
            SellableStore sellable = sellableStore();
            UUID v1 = validatedRelease(sellable.store());
            publish(sellable.store(), v1, UUID.randomUUID().toString(), "{}").andExpect(status().isOk());
            UUID first = validatedRelease(sellable.store());
            UUID second = validatedRelease(sellable.store());

            List<Integer> statuses = Race.run(List.of(
                    () -> publish(sellable.store(), first, UUID.randomUUID().toString(), "{}")
                            .andReturn().getResponse().getStatus(),
                    () -> publish(sellable.store(), second, UUID.randomUUID().toString(), "{}")
                            .andReturn().getResponse().getStatus()));

            assertThat(statuses).as("round %d", round).contains(200).allMatch(s -> s == 200 || s == 409);
            assertThat(jdbc.sql("SELECT count(*) FROM catalog.configuration_release WHERE store_id = :store"
                            + " AND status = 'PUBLISHED'").param("store", sellable.store()).query(Long.class).single())
                    .isEqualTo(1);
            assertThat(jdbc.sql("SELECT status FROM catalog.configuration_release WHERE id = :id").param("id", v1)
                    .query(String.class).single()).isEqualTo("RETIRED");
        }
    }

    @Test
    void racingOneReleaseUnderTwoKeysPublishesItOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            SellableStore sellable = sellableStore();
            UUID release = validatedRelease(sellable.store());

            List<Integer> statuses = Race.twice(() -> publish(sellable.store(), release, UUID.randomUUID().toString(),
                    "{}").andReturn().getResponse().getStatus());

            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(200, 409);
            assertThat(publications(release)).isEqualTo(1);
        }
    }

    @Test
    void theSnapshotHoldsWhatTheTillNeedsWithMoneyAsStrings() throws Exception {
        SellableStore sellable = sellableStore();
        JsonNode variant = fixtures().variant(id(sellable.item()));
        fixtures().price(sellable.store(), sellable.priceList(), id(sellable.item()), id(variant), "15.00");
        fixtures().created(post(CATALOG + "/barcodes").with(admin(Permission.CATALOG_MANAGE)), """
                {"code": "%s", "itemId": "%s", "variantId": "%s"}""".formatted(CatalogFixtures.code("560"),
                id(sellable.item()), id(variant)));
        JsonNode inactive = fixtures().item();
        deactivate(inactive);
        UUID release = validatedRelease(sellable.store());
        publish(sellable.store(), release, UUID.randomUUID().toString(), "{}").andExpect(status().isOk());

        JsonNode catalog = json(posCatalog(sellable.store())).get("catalog");

        assertThat(catalog.get("storeId").asString()).isEqualTo(sellable.store().toString());
        assertThat(catalog.get("releaseId").asString()).isEqualTo(release.toString());
        assertThat(catalog.get("version").asLong()).isEqualTo(1);
        assertThat(catalog.get("currency").asString()).isEqualTo("MZN");
        JsonNode item = itemIn(catalog, id(sellable.item()));
        assertThat(catalog.get("items").findValues("id").stream().map(JsonNode::asString))
                .doesNotContain(id(inactive).toString()); // inactive items are not sold
        assertThat(item.get("code").asString()).isEqualTo(sellable.item().get("code").asString());
        assertThat(item.get("productionDestination").asString()).isEqualTo("BAR");
        assertThat(item.get("price").get("amount").isString()).isTrue();
        assertThat(item.get("price").get("amount").asString()).isEqualTo("12.50");
        assertThat(item.get("price").get("currency").asString()).isEqualTo("MZN");
        assertThat(item.get("variants").get(0).get("price").get("amount").asString()).isEqualTo("15.00");
        assertThat(item.get("barcodes").get(0).get("variantId").asString()).isEqualTo(id(variant).toString());
        assertThat(catalog.get("families").findValues("id").stream().map(JsonNode::asString))
                .contains(sellable.item().get("familyId").asString());
        assertThat(catalog.get("units").findValues("id").stream().map(JsonNode::asString))
                .contains(sellable.item().get("unitId").asString());
        assertThat(catalog.get("taxCategories").valueStream()
                .filter(category -> category.get("id").asString().equals(item.get("taxCategoryId").asString()))
                .findFirst().orElseThrow().get("ratePercent").asString()).isEqualTo("16");
    }

    @Test
    void theSnapshotFreezesEachCategorysRateScheduleAndTheRateInEffectAtPublication() throws Exception {
        SellableStore sellable = sellableStore();
        UUID category = untaxedCategory();
        addRate(category, "5", "2000-01-01T00:00:00Z");
        addRate(category, "20", "2999-01-01T00:00:00Z");
        addRate(category, "10", "2020-01-01T00:00:00Z");
        JsonNode item = fixtures().item(fixtures().family(), fixtures().unit(), category);
        fixtures().price(sellable.store(), sellable.priceList(), id(item), null, "4.00");
        UUID release = validatedRelease(sellable.store());
        publish(sellable.store(), release, UUID.randomUUID().toString(), "{}").andExpect(status().isOk());

        JsonNode catalog = json(posCatalog(sellable.store())).get("catalog");
        JsonNode frozen = catalog.get("taxCategories").valueStream()
                .filter(node -> node.get("id").asString().equals(category.toString())).findFirst().orElseThrow();

        // the latest rate that started at or before publication: not the oldest, not the future one
        assertThat(frozen.get("ratePercent").asString()).isEqualTo("10");
        assertThat(frozen.get("rateEffectiveFrom").asString()).isEqualTo("2020-01-01T00:00:00Z");
        // the whole schedule, so orders (R2) pick the rate in effect at line confirmation (spec §8.4)
        assertThat(frozen.get("rates").valueStream().map(rate -> rate.get("ratePercent").asString() + "@"
                + rate.get("effectiveFrom").asString())).containsExactly("5@2000-01-01T00:00:00Z",
                "10@2020-01-01T00:00:00Z", "20@2999-01-01T00:00:00Z");
        assertThat(itemIn(catalog, id(item)).has("taxRatePercent")).isFalse();
    }

    @Test
    void aTaxCategoryWithOnlyAFutureRateHasNoRateInEffect() throws Exception {
        SellableStore sellable = sellableStore();
        UUID category = untaxedCategory();
        addRate(category, "20", "2999-01-01T00:00:00Z");
        JsonNode item = fixtures().item(fixtures().family(), fixtures().unit(), category);
        try {
            fixtures().price(sellable.store(), sellable.priceList(), id(item), null, "4.00");
            UUID release = id(createDraft(sellable.store()));

            mvc.perform(post(RELEASES + "/" + release + "/validate")
                            .with(inStore(sellable.store(), Permission.CATALOG_MANAGE)).with(csrf()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.itemId == '%s')].code".formatted(id(item)))
                            .value("ITEM_WITHOUT_TAX_RATE"));
        } finally {
            deactivate(item); // company-wide: it would block every later publication
        }
    }

    private void addRate(UUID category, String percent, String effectiveFrom) throws Exception {
        fixtures().created(post(CATALOG + "/tax-categories/" + category + "/rates").with(admin(Permission.CATALOG_MANAGE)),
                "{\"ratePercent\": %s, \"effectiveFrom\": \"%s\"}".formatted(percent, effectiveFrom));
    }

    private static JsonNode itemIn(JsonNode catalog, UUID itemId) {
        return catalog.get("items").valueStream()
                .filter(item -> item.get("id").asString().equals(itemId.toString()))
                .findFirst().orElseThrow(() -> new AssertionError("item " + itemId + " not in the snapshot"));
    }

    private SellableStore sellableStore() throws Exception {
        return publications().sellableStore();
    }

    private UUID untaxedCategory() throws Exception {
        return id(fixtures().created(post(CATALOG + "/tax-categories").with(admin(Permission.CATALOG_MANAGE)),
                "{\"code\": \"%s\", \"name\": \"No rate yet\"}".formatted(CatalogFixtures.code("T"))));
    }

    private JsonNode createDraft(UUID store) throws Exception {
        return publications().createDraft(store);
    }

    private UUID validatedRelease(UUID store) throws Exception {
        return publications().validatedRelease(store);
    }

    private ResultActions publish(UUID store, UUID release, String key, String body) throws Exception {
        return publications().publish(store, release, key, body);
    }

    private void changePrice(SellableStore sellable, String amount) throws Exception {
        mvc.perform(put(CATALOG + "/price-lists/" + sellable.priceList() + "/prices/" + id(sellable.price()))
                        .with(inStore(sellable.store(), Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": %s, \"version\": %d}".formatted(amount, currentPriceVersion(sellable))))
                .andExpect(status().isOk());
    }

    private long currentPriceVersion(SellableStore sellable) {
        return jdbc.sql("SELECT version FROM catalog.item_price WHERE id = :id").param("id", id(sellable.price()))
                .query(Long.class).single();
    }

    private void deactivate(JsonNode item) throws Exception {
        mvc.perform(put(CATALOG + "/items/" + id(item)).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"code": "%s", "name": "Old", "familyId": "%s", "unitId": "%s", "taxCategoryId": "%s",
                                 "availability": "INACTIVE", "soldByWeight": false, "version": 0}"""
                                .formatted(item.get("code").asString(), item.get("familyId").asString(),
                                        item.get("unitId").asString(), item.get("taxCategoryId").asString())))
                .andExpect(status().isOk());
    }

    private String posCatalog(UUID store) throws Exception {
        return mvc.perform(get(POS_CATALOG).with(pos(store, Permission.CATALOG_VIEW)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String snapshotText(UUID release) {
        return jdbc.sql("SELECT snapshot::text FROM catalog.configuration_release WHERE id = :id")
                .param("id", release).query(String.class).single();
    }

    private long publications(UUID release) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'CATALOG_RELEASE_PUBLISHED' AND entity_id = :id")
                .param("id", release.toString()).query(Long.class).single();
    }

    private Publications publications() {
        return new Publications(mvc, jdbc);
    }

    private CatalogFixtures fixtures() {
        return new CatalogFixtures(mvc);
    }
}
