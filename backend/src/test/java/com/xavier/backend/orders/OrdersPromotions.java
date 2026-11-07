package com.xavier.backend.orders;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.promotions.PromotionsFixtures.PROMOTIONS;
import static com.xavier.backend.promotions.PromotionsFixtures.expect;
import static com.xavier.backend.promotions.PromotionsFixtures.json;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.xavier.backend.access.Permission;
import com.xavier.backend.promotions.PromotionsFixtures;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import tools.jackson.databind.JsonNode;

/** Promotions for an orders {@link OrdersFixtures.Shop}, built through the promotions BackOffice HTTP API (S39). */
@TestComponent
public class OrdersPromotions {

    private final PromotionsFixtures promotions;

    OrdersPromotions(PromotionsFixtures promotions) {
        this.promotions = promotions;
    }

    /**
     * A promotion of the shop's store scoped to {@code item}: {@code valuesJson} holds its kind fields (for example
     * {@code "kind": "PERCENT_OFF", "percent": 20}); {@code windowsJson} its windows or {@code null}. Not published.
     */
    public JsonNode promotion(UUID store, UUID item, String code, String valuesJson, String windowsJson)
            throws Exception {
        JsonNode created = json(expect(promotions.create(store, """
                {"code": "%s", "name": "Promo %s", %s, "priority": 1}""".formatted(code, code, valuesJson)), 201));
        long version = created.get("version").asLong();
        UUID id = PromotionsFixtures.id(created);
        version = json(expect(promotions.perform(put(PROMOTIONS + "/" + id + "/scope")
                .with(admin(Permission.PROMOTION_MANAGE)), """
                {"itemIds": ["%s"], "version": %d}""".formatted(item, version)), 200)).get("version").asLong();
        if (windowsJson != null) {
            version = json(expect(promotions.perform(put(PROMOTIONS + "/" + id + "/windows")
                    .with(admin(Permission.PROMOTION_MANAGE)), """
                    {"windows": %s, "version": %d}""".formatted(windowsJson, version)), 200)).get("version")
                    .asLong();
        }
        return json(expect(promotions.perform(put(PROMOTIONS + "/" + id + "/stores")
                .with(admin(Permission.PROMOTION_MANAGE)), """
                {"storeIds": ["%s"], "version": %d}""".formatted(store, version)), 200));
    }

    /** The store's discount limits in the working copy (published with the next release, D106). */
    public void limits(UUID store, String freeLimitPercent, String maxPercent) throws Exception {
        expect(promotions.perform(put(PROMOTIONS + "/discount-limits/" + store)
                .with(inStore(store, Permission.PROMOTION_MANAGE)), """
                {"freeLimitPercent": %s, "maxPercent": %s}""".formatted(freeLimitPercent, maxPercent)), 200);
    }

    /** Publishes a new promotion release of the store; returns its version. */
    public long publish(UUID store) throws Exception {
        return promotions.published(store).get("version").asLong();
    }

    public static String code(String prefix) {
        return PromotionsFixtures.code(prefix);
    }
}
