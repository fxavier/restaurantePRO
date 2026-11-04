package com.xavier.backend.catalog.model.dto;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The frozen catalog of one store, stored as jsonb when a release is published (D31). It never changes after
 * publication; orders (R2) copy name, price and tax from it onto a line at confirmation. Money is written as
 * strings ({@code "12.50"}, currency {@code "MZN"}) so no client parses it as a float. Lists are ordered by code.
 *
 * @param schemaVersion   shape version of this document (1)
 * @param currency        ISO-4217 code of every price
 * @param families        all families and subfamilies
 * @param units           all units
 * @param taxCategories   categories used by the items, with the rate in effect at {@code effectiveAt}
 * @param items           ACTIVE items only
 */
public record CatalogSnapshot(int schemaVersion, UUID storeId, UUID releaseId, long version, Instant effectiveAt,
                              String currency, List<Family> families, List<Unit> units,
                              List<TaxCategory> taxCategories, List<Item> items) {

    public static final int SCHEMA_VERSION = 1;

    /** {@code parentId} is set for a subfamily. */
    public record Family(UUID id, String code, String name, UUID parentId) {
    }

    public record Unit(UUID id, String code, String name) {
    }

    /** {@code ratePercent} is a decimal percent as a string ("16" = 16%). */
    public record TaxCategory(UUID id, String code, String name, String ratePercent, Instant rateEffectiveFrom) {
    }

    /**
     * @param taxRatePercent        the item's tax rate in effect at publication, copied from its category
     * @param productionDestination KITCHEN, BAR… or {@code null} when nothing is prepared
     * @param price                 the item's price in the store's price list
     */
    public record Item(UUID id, String code, String name, UUID familyId, UUID unitId, UUID taxCategoryId,
                       String taxRatePercent, String productionDestination, boolean soldByWeight, Price price,
                       List<Variant> variants, List<Barcode> barcodes) {
    }

    /** {@code price} is {@code null} when the variant has no own price: it sells at the item price. */
    public record Variant(UUID id, String code, String name, Price price) {
    }

    /** {@code variantId} is {@code null} when the barcode identifies the item itself. */
    public record Barcode(String code, UUID variantId) {
    }

    public record Price(String amount, String currency) {

        public static Price of(Money money) {
            return new Price(money.amount().toPlainString(), money.currency().getCurrencyCode());
        }
    }
}
