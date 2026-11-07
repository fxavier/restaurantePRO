package com.xavier.backend.promotions;

import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.promotions.model.dto.DiscountLimits;
import com.xavier.backend.promotions.model.dto.PricedLine;
import com.xavier.backend.promotions.model.dto.PricingRequest;
import com.xavier.backend.promotions.model.dto.PricingResult;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot;
import com.xavier.backend.promotions.service.PublishedPromotions;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What orders asks promotions (D100): prices from the store's current published promotion release, its version and
 * its discount limits. Read-only and without authorization: callers authorize their own use case.
 */
@Service
@Transactional(readOnly = true)
public class PromotionsApi {

    /** The limits while a store has no published promotion release (D106): no free discount, at most 100 %. */
    public static final DiscountLimits NO_RELEASE_LIMITS = new DiscountLimits(BigDecimal.ZERO, BigDecimal.valueOf(100));

    private final PublishedPromotions published;
    private final OrganizationApi organization;

    PromotionsApi(PublishedPromotions published, OrganizationApi organization) {
        this.published = published;
        this.organization = organization;
    }

    /**
     * Prices {@code request} with {@link PromotionEngine} in the store's zone (D103). Without a published release every
     * line keeps its list price and the result has no release id or version.
     */
    public PricingResult price(PricingRequest request) {
        Optional<PromotionSnapshot> snapshot = published.current(request.storeId());
        if (snapshot.isEmpty()) {
            return new PricingResult(null, null, request.lines().stream()
                    .map(line -> new PricedLine(line.key(), line.listUnitPrice(), line.listUnitPrice(), null))
                    .toList());
        }
        return PromotionEngine.evaluate(snapshot.get(), request, organization.zoneOf(request.storeId()));
    }

    /** The version of the store's published promotion release; empty when it has none. */
    public OptionalLong currentVersion(UUID storeId) {
        return published.currentVersion(storeId);
    }

    /** The limits frozen in the store's published release, or {@link #NO_RELEASE_LIMITS}. */
    public DiscountLimits discountLimits(UUID storeId) {
        return published.current(storeId).map(PromotionSnapshot::discountLimits).orElse(NO_RELEASE_LIMITS);
    }
}
