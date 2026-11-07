package com.xavier.backend.promotions;

import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.promotions.PromotionsFixtures.PROMOTIONS;
import static com.xavier.backend.promotions.PromotionsFixtures.expect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.promotions.PromotionsFixtures.Shop;
import com.xavier.backend.promotions.model.dto.DiscountLimits;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * D106, P44: the limits orders reads are those frozen in the store's published promotion release. Without a release
 * they are 0 % free and 100 % max; a release of a store that never set its own freezes the P44 10 / 50.
 */
@PromotionsModuleTest
class DiscountLimitsDefaultTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PromotionsFixtures fixtures;

    @Autowired
    PromotionsApi promotionsApi;

    @Test
    void withoutAPublishedReleaseNothingIsFreeAndAHundredPercentIsTheMaximum() throws Exception {
        UUID store = fixtures.storeWithoutCatalog();

        assertLimits(promotionsApi.discountLimits(store), "0", "100");
        assertThat(promotionsApi.currentVersion(store)).isEmpty();
    }

    @Test
    void aReleaseOfAStoreWithoutItsOwnLimitsFreezesTheP44Defaults() throws Exception {
        Shop shop = fixtures.shop();
        mvc.perform(get(limitsOf(shop.store())).with(inStore(shop.store(), Permission.PROMOTION_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freeLimitPercent").value(10))
                .andExpect(jsonPath("$.maxPercent").value(50))
                .andExpect(jsonPath("$.version").isEmpty());

        fixtures.published(shop.store());

        assertLimits(promotionsApi.discountLimits(shop.store()), "10", "50");
    }

    @Test
    void aStoresOwnLimitsApplyOnlyFromItsNextPublication() throws Exception {
        Shop shop = fixtures.shop();
        fixtures.published(shop.store());

        expect(putLimits(shop.store(), "15", "40", null), 200);
        assertLimits(promotionsApi.discountLimits(shop.store()), "10", "50");

        fixtures.published(shop.store());
        assertLimits(promotionsApi.discountLimits(shop.store()), "15", "40");
    }

    @Test
    void limitsOutsideZeroToAHundredOrAMaximumBelowTheFreeLimitAreRefused() throws Exception {
        UUID store = fixtures.storeWithoutCatalog();

        for (String[] limits : new String[][] {{"20", "10"}, {"-1", "50"}, {"10", "101"}, {"10.001", "50"}}) {
            putLimits(store, limits[0], limits[1], null).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("DISCOUNT_LIMITS_INVALID"));
        }
    }

    @Test
    void changingLimitsNeedsTheVersionReadAndTheStoresPermission() throws Exception {
        UUID store = fixtures.storeWithoutCatalog();
        UUID other = fixtures.storeWithoutCatalog();
        expect(putLimits(store, "10", "50", null), 200).andExpect(jsonPath("$.version").value(0));

        putLimits(store, "12", "50", null).andExpect(status().isConflict());
        putLimits(store, "12", "50", 3L).andExpect(status().isConflict());
        expect(putLimits(store, "12", "50", 0L), 200).andExpect(jsonPath("$.version").value(1));
        fixtures.perform(put(limitsOf(store)).with(inStore(other, Permission.PROMOTION_MANAGE)),
                "{\"freeLimitPercent\": 5, \"maxPercent\": 50}").andExpect(status().isForbidden());
    }

    private ResultActions putLimits(UUID store, String free, String max, Long version) throws Exception {
        return fixtures.perform(put(limitsOf(store)).with(inStore(store, Permission.PROMOTION_MANAGE)), """
                {"freeLimitPercent": %s, "maxPercent": %s, "version": %s}""".formatted(free, max, version));
    }

    private static String limitsOf(UUID store) {
        return PROMOTIONS + "/discount-limits/" + store;
    }

    private static void assertLimits(DiscountLimits limits, String free, String max) {
        assertThat(limits.freeLimitPercent()).isEqualByComparingTo(new BigDecimal(free));
        assertThat(limits.maxPercent()).isEqualByComparingTo(new BigDecimal(max));
    }
}
