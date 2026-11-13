package com.xavier.backend.promotions.service;

import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.Actors.pos;
import static com.xavier.backend.promotions.PromotionsFixtures.code;
import static com.xavier.backend.promotions.PromotionsFixtures.id;
import static com.xavier.backend.promotions.PromotionsFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogFixtures;
import com.xavier.backend.catalog.Exclusions;
import com.xavier.backend.catalog.Publications;
import com.xavier.backend.catalog.Publications.SellableStore;
import com.xavier.backend.promotions.PromotionsApi;
import com.xavier.backend.promotions.PromotionsFixtures;
import com.xavier.backend.promotions.PromotionsModuleTest;
import com.xavier.backend.promotions.model.dto.PricingLine;
import com.xavier.backend.promotions.model.dto.PricingRequest;
import com.xavier.backend.promotions.model.dto.PricingResult;
import com.xavier.backend.promotions.model.dto.PromotionReleaseError;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * D147 promotions row (amended 2026-11-13, S58 review): a scope item or variant that store B's published catalog lacks
 * because B excludes the item does not apply in B and is a warning; anything else the catalog lacks still blocks the
 * release with {@code SCOPE_NOT_IN_CATALOG}.
 */
@PromotionsModuleTest
class PromotionScopeExclusionTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PromotionsFixtures fixtures;

    @Autowired
    PromotionsApi promotionsApi;

    @Autowired
    PromotionWorkingCopy workingCopy;

    @Autowired
    org.springframework.transaction.support.TransactionTemplate transactions;

    @Test
    void aScopeItemTheStoreExcludesDoesNotApplyThereAndIsAWarning() throws Exception {
        SellableStore b = publications().sellableStore();
        CatalogFixtures catalog = new CatalogFixtures(mvc);
        JsonNode x = catalog.item();
        JsonNode large = catalog.variant(id(x));
        JsonNode y = catalog.item();
        catalog.price(b.store(), b.priceList(), id(y), null, "12.50");
        new Exclusions(mvc).exclude(b.store(), id(x), null).andExpect(status().isOk());
        publications().publishNew(b.store());
        JsonNode promotion = fixtures.promotion(b.store(), code("EX"), """
                {"itemIds": ["%s", "%s"], "variantIds": ["%s"]}""".formatted(id(x), id(y), id(large)));

        assertThat(warnings(b.store()))
                .extracting(PromotionReleaseError::code, PromotionReleaseError::promotionId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PROMOTION_SCOPE_ITEM_EXCLUDED", id(promotion)),
                        org.assertj.core.groups.Tuple.tuple("PROMOTION_SCOPE_ITEM_EXCLUDED", id(promotion)));
        UUID release = id(json(fixtures.createRelease(b.store())));
        mvc.perform(get(PromotionsFixtures.RELEASES + "/" + release)
                        .with(inStore(b.store(), Permission.PROMOTION_MANAGE)))
                .andExpect(jsonPath("$.validationWarnings").value(org.hamcrest.Matchers.nullValue()));
        fixtures.validate(b.store(), release).andExpect(status().isOk())
                .andExpect(jsonPath("$.validationWarnings.length()").value(2))
                .andExpect(jsonPath("$.validationWarnings[0].code").value("PROMOTION_SCOPE_ITEM_EXCLUDED"))
                .andExpect(jsonPath("$.validationWarnings[1].promotionId").value(id(promotion).toString()));
        fixtures.publish(b.store(), release).andExpect(status().isOk())
                .andExpect(jsonPath("$.validationWarnings.length()").value(2))
                .andExpect(jsonPath("$.validationWarnings[0].code").value("PROMOTION_SCOPE_ITEM_EXCLUDED"));
        mvc.perform(get(PromotionsFixtures.RELEASES + "/" + release)
                        .with(inStore(b.store(), Permission.PROMOTION_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.validationWarnings.length()").value(2))
                .andExpect(jsonPath("$.validationWarnings[1].code").value("PROMOTION_SCOPE_ITEM_EXCLUDED"));

        mvc.perform(get("/api/v1/pos/promotions/current").with(pos(b.store(), Permission.CATALOG_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(release.toString()))
                .andExpect(jsonPath("$.promotions[?(@.id == '%s')].scope.itemIds[*]".formatted(id(promotion)))
                        .value(id(y).toString()))
                .andExpect(jsonPath("$.promotions[?(@.id == '%s')].scope.variantIds.length()".formatted(id(promotion)))
                        .value(0));
        Money list = Money.of("12.50", Currency.getInstance("MZN"));
        PricingResult priced = promotionsApi.price(new PricingRequest(b.store(), Instant.parse("2026-11-06T12:00:00Z"),
                "DINE_IN", false, List.of(new PricingLine(UUID.randomUUID(), id(y), null,
                UUID.fromString(y.get("familyId").asString()), list, BigDecimal.ONE))));
        assertThat(priced.lines().getFirst().unitPrice()).isEqualTo(Money.of("10.00", list.currency()));
    }

    @Test
    void aScopeItemMissingForAnyOtherReasonStillBlocksTheRelease() throws Exception {
        SellableStore b = publications().sellableStore();
        UUID other = new CatalogFixtures(mvc).store();
        publications().publishNew(b.store());
        JsonNode unpublished = new CatalogFixtures(mvc).item(); // after the publication: not in it, not excluded
        JsonNode excludedElsewhere = new CatalogFixtures(mvc).item();
        new Exclusions(mvc).exclude(other, id(excludedElsewhere), null).andExpect(status().isOk());
        fixtures.promotion(b.store(), code("MISS"), """
                {"itemIds": ["%s", "%s"]}""".formatted(id(unpublished), id(excludedElsewhere)));
        UUID release = id(json(fixtures.createRelease(b.store())));

        fixtures.validate(b.store(), release).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("SCOPE_NOT_IN_CATALOG"))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString(id(unpublished).toString()),
                        org.hamcrest.Matchers.containsString(id(excludedElsewhere).toString()))));
        assertThat(warnings(b.store())).isEmpty();
    }

    private List<PromotionReleaseError> warnings(UUID store) {
        return transactions.execute(tx -> workingCopy.of(store).warnings());
    }

    private Publications publications() {
        return new Publications(mvc, jdbc);
    }
}
