package com.xavier.backend.promotions;

import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.Actors.pos;
import static com.xavier.backend.promotions.PromotionsFixtures.PROMOTIONS;
import static com.xavier.backend.promotions.PromotionsFixtures.code;
import static com.xavier.backend.promotions.PromotionsFixtures.expect;
import static com.xavier.backend.promotions.PromotionsFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.promotions.PromotionsFixtures.Shop;
import com.xavier.backend.promotions.model.dto.PricedLine;
import com.xavier.backend.promotions.model.dto.PricingLine;
import com.xavier.backend.promotions.model.dto.PricingRequest;
import com.xavier.backend.promotions.model.dto.PricingResult;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/** D100, D103, A38 (engine half): orders prices a round through PromotionsApi, in the store's zone. */
@PromotionsModuleTest
class PromotionsApiTest {

    static final Money LIST = Money.of("12.50", Currency.getInstance("MZN"));
    static final ZoneId MAPUTO = ZoneId.of("Africa/Maputo"); // the fixtures' store zone

    @Autowired
    MockMvc mvc;

    @Autowired
    PromotionsFixtures fixtures;

    @Autowired
    PromotionsApi promotionsApi;

    @Test
    void withoutAPublishedReleaseEveryLineKeepsItsListPrice() throws Exception {
        UUID store = fixtures.storeWithoutCatalog();
        PricingLine line = new PricingLine(UUID.randomUUID(), UUID.randomUUID(), null, null, LIST, BigDecimal.TWO);

        PricingResult result = promotionsApi.price(new PricingRequest(store, Instant.parse("2026-11-06T12:00:00Z"),
                "DINE_IN", false, List.of(line)));

        assertThat(result.releaseId()).isNull();
        assertThat(result.version()).isNull();
        assertThat(result.lines()).containsExactly(new PricedLine(line.key(), LIST, LIST, null));
        mvc.perform(get("/api/v1/pos/promotions/current").with(pos(store, Permission.CATALOG_VIEW)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_PUBLISHED_PROMOTIONS"));
    }

    /** A38: 20 % on the family, 18:00–20:00 store time: 19:59 gets 20 % off per unit, 20:00 the list price. */
    @Test
    void aHappyHourOnTheFamilyEndsAtTheStoresTwentyHundred() throws Exception {
        Shop shop = fixtures.shop();
        JsonNode promotion = fixtures.promotion(shop.store(), code("HH"), """
                {"familyIds": ["%s"]}""".formatted(shop.family()));
        expect(fixtures.perform(put(PROMOTIONS + "/" + id(promotion) + "/windows")
                .with(inStore(shop.store(), Permission.PROMOTION_MANAGE)), """
                {"windows": [{"daysOfWeek": [1, 2, 3, 4, 5, 6, 7], "start": "18:00", "end": "20:00"}], "version": %d}"""
                .formatted(promotion.get("version").asLong())), 200);
        fixtures.published(shop.store());

        PricedLine at1959 = priceAt(shop, LocalDateTime.parse("2026-11-06T19:59:00"));
        PricedLine at2000 = priceAt(shop, LocalDateTime.parse("2026-11-06T20:00:00"));

        assertThat(at1959.unitPrice()).isEqualTo(Money.of("10.00", LIST.currency()));
        assertThat(at1959.promotion().code()).isEqualTo(promotion.get("code").asString());
        assertThat(at2000.unitPrice()).isEqualTo(LIST);
        assertThat(at2000.promotion()).isNull();
    }

    private PricedLine priceAt(Shop shop, LocalDateTime storeTime) {
        return promotionsApi.price(new PricingRequest(shop.store(), storeTime.atZone(MAPUTO).toInstant(), "DINE_IN",
                false, List.of(new PricingLine(UUID.randomUUID(), shop.item(), null, shop.family(), LIST,
                BigDecimal.ONE)))).lines().getFirst();
    }
}
