package com.xavier.backend.promotions;

import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.Actors.pos;
import static com.xavier.backend.promotions.PromotionsFixtures.RELEASES;
import static com.xavier.backend.promotions.PromotionsFixtures.code;
import static com.xavier.backend.promotions.PromotionsFixtures.id;
import static com.xavier.backend.promotions.PromotionsFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.promotions.PromotionsFixtures.Shop;
import com.xavier.backend.promotions.model.dto.PricingLine;
import com.xavier.backend.promotions.model.dto.PricingRequest;
import com.xavier.backend.promotions.model.dto.PricingResult;
import com.xavier.backend.promotions.model.event.PromotionsPublished;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.AssertablePublishedEvents;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/** D102: promotion releases per store, DRAFT → VALIDATED → PUBLISHED → RETIRED, frozen like the catalog. */
@PromotionsModuleTest
class PromotionReleaseLifecycleTest {

    static final Money LIST = Money.of("12.50", Currency.getInstance("MZN"));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PromotionsFixtures fixtures;

    @Autowired
    PromotionsApi promotionsApi;

    @Test
    void publishingFreezesTheStoresPromotionsAndAnnouncesIt(AssertablePublishedEvents events) throws Exception {
        Shop shop = fixtures.shop();
        JsonNode promotion = fixtures.promotion(shop.store(), code("HH"), """
                {"familyIds": ["%s"]}""".formatted(shop.family()));

        JsonNode draft = json(fixtures.createRelease(shop.store()).andExpect(status().isCreated()));
        assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
        UUID release = id(draft);
        fixtures.validate(shop.store(), release).andExpect(jsonPath("$.status").value("VALIDATED"));
        fixtures.publish(shop.store(), release).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(events.ofType(PromotionsPublished.class)
                .matching(event -> event.releaseId().equals(release) && event.storeId().equals(shop.store())
                        && event.version() == 1)).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'PROMOTION_RELEASE_PUBLISHED'"
                + " AND entity_id = :id AND store_id = :store").param("id", release.toString())
                .param("store", shop.store()).query(Long.class).single()).isEqualTo(1);
        mvc.perform(get("/api/v1/pos/promotions/current").with(pos(shop.store(), Permission.CATALOG_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(release.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.currency").value("MZN"))
                .andExpect(jsonPath("$.promotions[0].id").value(id(promotion).toString()))
                .andExpect(jsonPath("$.promotions[0].percent").value("20"))
                .andExpect(jsonPath("$.discountLimits.freeLimitPercent").value(10));
        PricingResult priced = promotionsApi.price(new PricingRequest(shop.store(), Instant.parse("2026-11-06T12:00:00Z"),
                "DINE_IN", false, List.of(new PricingLine(UUID.randomUUID(), shop.item(), null, shop.family(), LIST,
                BigDecimal.ONE))));
        assertThat(priced.releaseId()).isEqualTo(release);
        assertThat(priced.version()).isEqualTo(1L);
        assertThat(priced.lines().getFirst().unitPrice()).isEqualTo(Money.of("10.00", LIST.currency()));
        assertThat(priced.lines().getFirst().promotion().promotionId()).isEqualTo(id(promotion));
    }

    @Test
    void theNextPublicationRetiresThePreviousOneAndLeavesItsSnapshotUntouched() throws Exception {
        Shop shop = fixtures.shop();
        UUID first = id(fixtures.published(shop.store()));
        String frozen = snapshotOf(first);
        fixtures.promotion(shop.store(), code("NEW"), """
                {"itemIds": ["%s"]}""".formatted(shop.item()));

        JsonNode second = fixtures.published(shop.store());

        assertThat(second.get("version").asLong()).isEqualTo(2);
        mvc.perform(get(RELEASES + "/" + first).with(inStore(shop.store(), Permission.PROMOTION_MANAGE)))
                .andExpect(jsonPath("$.status").value("RETIRED"))
                .andExpect(jsonPath("$.retiredAt").isNotEmpty());
        assertThat(snapshotOf(first)).isEqualTo(frozen);
        assertThat(promotionsApi.currentVersion(shop.store())).hasValue(2);
    }

    @Test
    void aDraftCannotBePublishedAndAPublishedReleaseCannotBeValidated() throws Exception {
        Shop shop = fixtures.shop();
        UUID draft = id(json(fixtures.createRelease(shop.store())));

        fixtures.publish(shop.store(), draft).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTION_RELEASE_STATE"));
        fixtures.validate(shop.store(), draft).andExpect(status().isOk());
        fixtures.publish(shop.store(), draft).andExpect(status().isOk());
        fixtures.validate(shop.store(), draft).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTION_RELEASE_STATE"));
    }

    @Test
    void validationListsEveryScopeEntryTheStoresPublishedCatalogDoesNotSell() throws Exception {
        Shop shop = fixtures.shop();
        UUID unknown = UUID.randomUUID();
        JsonNode promotion = fixtures.promotion(shop.store(), code("BAD"), """
                {"itemIds": ["%s", "%s"]}""".formatted(shop.item(), unknown));
        UUID release = id(json(fixtures.createRelease(shop.store())));

        fixtures.validate(shop.store(), release).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PROMOTION_RELEASE_INVALID"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("SCOPE_NOT_IN_CATALOG"))
                .andExpect(jsonPath("$.errors[0].promotionId").value(id(promotion).toString()));
        mvc.perform(get(RELEASES + "/" + release).with(inStore(shop.store(), Permission.PROMOTION_MANAGE)))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void aStoreWithoutAPublishedCatalogCannotValidate() throws Exception {
        UUID store = fixtures.storeWithoutCatalog();
        UUID release = id(json(fixtures.createRelease(store).andExpect(status().isCreated())));

        fixtures.validate(store, release).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("NO_PUBLISHED_CATALOG"));
    }

    @Test
    void aReleaseIsReadByItsStoreFirstThenAuthorized() throws Exception {
        Shop shop = fixtures.shop();
        UUID other = fixtures.storeWithoutCatalog();
        UUID release = id(json(fixtures.createRelease(shop.store())));

        mvc.perform(get(RELEASES + "/" + UUID.randomUUID()).with(inStore(shop.store(), Permission.PROMOTION_MANAGE)))
                .andExpect(status().isNotFound());
        mvc.perform(get(RELEASES + "/" + release).with(inStore(other, Permission.PROMOTION_MANAGE)))
                .andExpect(status().isForbidden());
        fixtures.publish(other, release).andExpect(status().isForbidden());
        fixtures.perform(post(RELEASES).with(inStore(other, Permission.PROMOTION_MANAGE)),
                "{\"storeId\": \"%s\"}".formatted(shop.store())).andExpect(status().isForbidden());
        fixtures.perform(post(RELEASES).with(inStore(shop.store(), Permission.PROMOTION_VIEW)),
                "{\"storeId\": \"%s\"}".formatted(shop.store())).andExpect(status().isForbidden());
    }

    @Test
    void publishingIsRepeatableWithTheSameIdempotencyKey() throws Exception {
        Shop shop = fixtures.shop();
        UUID release = id(json(fixtures.createRelease(shop.store())));
        fixtures.validate(shop.store(), release).andExpect(status().isOk());
        String key = UUID.randomUUID().toString();

        String first = publishWith(shop.store(), release, key);
        String replay = publishWith(shop.store(), release, key);

        assertThat(replay).isEqualTo(first);
        mvc.perform(post(RELEASES + "/" + release + "/publish").with(inStore(shop.store(), Permission.PROMOTION_MANAGE))
                .with(csrf())).andExpect(status().isBadRequest());
    }

    private String publishWith(UUID store, UUID release, String key) throws Exception {
        return mvc.perform(post(RELEASES + "/" + release + "/publish").with(inStore(store, Permission.PROMOTION_MANAGE))
                        .with(csrf()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String snapshotOf(UUID release) {
        return jdbc.sql("SELECT snapshot::text FROM promotions.promotion_release WHERE id = :id")
                .param("id", release).query(String.class).single();
    }
}
