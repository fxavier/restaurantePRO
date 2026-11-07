package com.xavier.backend.orders.service;

import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.orders.model.CatalogPricing;
import com.xavier.backend.orders.model.LinePricing;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.OrderLine;
import com.xavier.backend.orders.model.PromotionRef;
import com.xavier.backend.promotions.LoyaltyApi;
import com.xavier.backend.promotions.PromotionsApi;
import com.xavier.backend.promotions.model.dto.PricedLine;
import com.xavier.backend.promotions.model.dto.PricingLine;
import com.xavier.backend.promotions.model.dto.PricingRequest;
import com.xavier.backend.promotions.model.dto.PricingResult;
import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Prices drafts the way confirmation fixes them (D101–D103): the catalog price (D58) is the list price, and
 * {@link PromotionsApi#price} turns it into the effective price at {@code at}, for the order's mode and whether its
 * customer is a loyalty member. A draft preview and a confirmation call the same method, so they agree for one
 * instant.
 */
@Component
public class LinePricer {

    private final PromotionsApi promotions;
    private final LoyaltyApi loyalty;

    LinePricer(PromotionsApi promotions, LoyaltyApi loyalty) {
        this.promotions = promotions;
        this.loyalty = loyalty;
    }

    /** The priced drafts by line id, and the promotion release they were priced from. */
    public record Priced(Long promotionVersion, Map<UUID, LinePricing> lines) {

        public Optional<LinePricing> of(UUID lineId) {
            return Optional.ofNullable(lines.get(lineId));
        }
    }

    /** Drafts whose item or variant left the catalog get no price. */
    public Priced price(Order order, CatalogSnapshot catalog, Collection<OrderLine> drafts, Instant at) {
        List<PricingLine> requested = new ArrayList<>();
        for (OrderLine draft : drafts) {
            Optional<Money> list = CatalogPricing.previewPrice(catalog, draft.getItemId(), draft.getVariantId());
            if (list.isEmpty()) {
                continue;
            }
            UUID family = catalog.items().stream().filter(item -> item.id().equals(draft.getItemId())).findFirst()
                    .map(CatalogSnapshot.Item::familyId).orElse(null);
            requested.add(new PricingLine(draft.getId(), draft.getItemId(), draft.getVariantId(), family, list.get(),
                    draft.getQuantity()));
        }
        if (requested.isEmpty()) {
            return new Priced(promotions.currentVersion(order.getStoreId()).stream().boxed().findFirst()
                    .orElse(null), Map.of());
        }
        PricingResult result = promotions.price(new PricingRequest(order.getStoreId(), at, order.getMode().name(),
                member(order), requested));
        Map<UUID, LinePricing> priced = new LinkedHashMap<>();
        for (PricedLine line : result.lines()) {
            PromotionRef promotion = line.promotion() == null ? null : new PromotionRef(line.promotion().promotionId(),
                    line.promotion().code(), line.promotion().name(), result.releaseId(), result.version());
            priced.put(line.key(), new LinePricing(line.listUnitPrice(), line.unitPrice(), promotion));
        }
        return new Priced(result.version(), priced);
    }

    /** The version of the store's published promotion release, {@code null} when it has none. */
    public Long currentVersion(UUID storeId) {
        return promotions.currentVersion(storeId).stream().boxed().findFirst().orElse(null);
    }

    private boolean member(Order order) {
        return order.getCustomerId() != null && loyalty.isMember(order.getCustomerId());
    }
}
