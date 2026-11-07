package com.xavier.backend.orders.delivery.service;

import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.catalog.model.dto.ItemKind;
import com.xavier.backend.orders.delivery.exception.DeliveryRuleException;
import com.xavier.backend.orders.delivery.model.DeliveryZone;
import com.xavier.backend.orders.model.CatalogPricing;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.service.DraftLineService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The fee of a delivery zone (D128, P48): a FEE item of the published catalog, added as one automatic draft, so it is
 * priced and taxed at confirmation from its own snapshot like any line (D58, D59).
 */
@Component
public class DeliveryFees {

    private final CatalogApi catalog;
    private final DraftLineService drafts;

    DeliveryFees(CatalogApi catalog, DraftLineService drafts) {
        this.catalog = catalog;
        this.drafts = drafts;
    }

    /** @throws DeliveryRuleException {@code FEE_ITEM_NOT_PUBLISHED} unless the zone's fee is a published FEE item */
    public void requirePublished(DeliveryZone zone) {
        CatalogSnapshot current = catalog.findCurrentSnapshot(zone.getStoreId())
                .orElseThrow(() -> DeliveryRuleException.feeItemNotPublished(zone.getCode()));
        if (CatalogPricing.previewPrice(current, zone.getFeeItemId(), zone.getFeeVariantId()).isEmpty()
                || CatalogPricing.itemOf(current, zone.getFeeItemId(), zone.getFeeVariantId()).kind()
                != ItemKind.FEE) {
            throw DeliveryRuleException.feeItemNotPublished(zone.getCode());
        }
    }

    /** Adds the zone's fee as an automatic draft of the order; the caller checked {@link #requirePublished}. */
    public void addTo(Order order, DeliveryZone zone, UUID employeeId) {
        drafts.addFeeDraft(order, zone.getFeeItemId(), zone.getFeeVariantId(), employeeId);
    }

    /** A zone swap: the order's fee drafts give way to the new zone's fee (a confirmed fee stays, P48). */
    public void replaceDraft(Order order, DeliveryZone zone, UUID employeeId) {
        drafts.removeFeeDrafts(order);
        addTo(order, zone, employeeId);
    }
}
