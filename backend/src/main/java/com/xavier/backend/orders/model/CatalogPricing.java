package com.xavier.backend.orders.model;

import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.orders.exception.OrderRuleException;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Prices a line from a frozen catalog (D58). Pure: the caller passes the catalog it read and the confirmation instant.
 * The price is the variant's own price or else the item price ({@code Item.priceOf}); the rate is the one the tax
 * category's schedule has in effect at that instant ({@code TaxCategory.rateAt}), never the published
 * {@code ratePercent}.
 */
public final class CatalogPricing {

    private CatalogPricing() {
    }

    /**
     * @throws OrderRuleException {@code ITEM_NOT_IN_CATALOG} when the item (or the variant of that item) is not in the
     *                            catalog, {@code NO_TAX_RATE_IN_EFFECT} when no rate of its category started by
     *                            {@code at}
     */
    public static LineSnapshot snapshotOf(CatalogSnapshot catalog, UUID itemId, UUID variantId, Instant at) {
        CatalogSnapshot.Item item = item(catalog, itemId)
                .orElseThrow(() -> OrderRuleException.itemNotInCatalog(itemId, variantId));
        String variantName = variantId == null ? null : variant(item, variantId)
                .orElseThrow(() -> OrderRuleException.itemNotInCatalog(itemId, variantId)).name();
        CatalogSnapshot.TaxCategory tax = catalog.taxCategories().stream()
                .filter(category -> category.id().equals(item.taxCategoryId())).findFirst()
                .orElseThrow(() -> OrderRuleException.noTaxRateInEffect(item.code()));
        BigDecimal rate = tax.rateAt(at).orElseThrow(() -> OrderRuleException.noTaxRateInEffect(item.code()));
        return new LineSnapshot(item.code(), item.name(), variantName, item.priceOf(variantId), tax.id(), tax.code(),
                rate, catalog.releaseId(), catalog.version(), item.productionDestination());
    }

    /** @throws OrderRuleException {@code ITEM_NOT_IN_CATALOG} when the item is not in the catalog */
    public static CatalogSnapshot.Item itemOf(CatalogSnapshot catalog, UUID itemId, UUID variantId) {
        return item(catalog, itemId).orElseThrow(() -> OrderRuleException.itemNotInCatalog(itemId, variantId));
    }

    /** The price a draft would get now; empty when the item or variant is no longer in the catalog. */
    public static Optional<Money> previewPrice(CatalogSnapshot catalog, UUID itemId, UUID variantId) {
        return item(catalog, itemId)
                .filter(item -> variantId == null || variant(item, variantId).isPresent())
                .map(item -> item.priceOf(variantId));
    }

    private static Optional<CatalogSnapshot.Item> item(CatalogSnapshot catalog, UUID itemId) {
        return catalog.items().stream().filter(item -> item.id().equals(itemId)).findFirst();
    }

    private static Optional<CatalogSnapshot.Variant> variant(CatalogSnapshot.Item item, UUID variantId) {
        return item.variants().stream().filter(variant -> variant.id().equals(variantId)).findFirst();
    }
}
