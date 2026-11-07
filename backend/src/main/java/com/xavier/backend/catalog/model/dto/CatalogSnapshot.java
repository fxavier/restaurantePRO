package com.xavier.backend.catalog.model.dto;

import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The frozen catalog of one store, stored as jsonb when a release is published (D31). It never changes after
 * publication; orders (R2) copy name, price and tax from it onto a line at confirmation. Money is written as
 * strings ({@code "12.50"}, currency {@code "MZN"}) so no client parses it as a float. Lists are ordered by code.
 *
 * <p>Tax is fixed when a line is confirmed (spec §8.4), not when the catalog is published: each tax category
 * carries its full rate schedule, and R2 picks the rate whose {@code effectiveFrom} ≤ confirmation instant,
 * the latest one.
 *
 * @param schemaVersion   shape version of this document (1)
 * @param currency        ISO-4217 code of every price
 * @param families        all families and subfamilies
 * @param units           all units
 * @param taxCategories   categories used by the items, with their rate schedule
 * @param items           ACTIVE items only
 * @param quickPages      the POS quick pages of this store and of every store (v2, D109)
 * @param macros          every macro (v2, D109)
 * @param automaticItems  the ACTIVE automatic items of this store and of every store (v2, D109)
 *
 * <p>Schema version 2 (R4) added item kinds and menu groups (D108) and the three lists above. A version-1 document
 * reads with every item {@code STANDARD}, no menu groups and empty lists.
 */
public record CatalogSnapshot(int schemaVersion, UUID storeId, UUID releaseId, long version, Instant effectiveAt,
                              String currency, List<Family> families, List<Unit> units,
                              List<TaxCategory> taxCategories, List<Item> items, List<QuickPage> quickPages,
                              List<Macro> macros, List<AutomaticItem> automaticItems) {

    public static final int SCHEMA_VERSION = 2;

    public CatalogSnapshot {
        quickPages = quickPages == null ? List.of() : quickPages;
        macros = macros == null ? List.of() : macros;
        automaticItems = automaticItems == null ? List.of() : automaticItems;
    }

    /** A catalog without the R4 configuration (the shape of schema version 1). */
    public CatalogSnapshot(int schemaVersion, UUID storeId, UUID releaseId, long version, Instant effectiveAt,
                           String currency, List<Family> families, List<Unit> units,
                           List<TaxCategory> taxCategories, List<Item> items) {
        this(schemaVersion, storeId, releaseId, version, effectiveAt, currency, families, units, taxCategories, items,
                List.of(), List.of(), List.of());
    }

    /** {@code parentId} is set for a subfamily. */
    public record Family(UUID id, String code, String name, UUID parentId) {
    }

    public record Unit(UUID id, String code, String name) {
    }

    /**
     * @param ratePercent       the rate in effect at publication (informational), a decimal percent as a string
     *                          ("16" = 16%)
     * @param rateEffectiveFrom when that rate started
     * @param rates             the whole schedule ordered by {@code effectiveFrom}, past and future: the source
     *                          for the rate at line confirmation
     */
    public record TaxCategory(UUID id, String code, String name, String ratePercent, Instant rateEffectiveFrom,
                              List<Rate> rates) {

        /**
         * The rate in effect at {@code instant} (D58): the entry with the greatest {@code effectiveFrom} ≤
         * {@code instant}. {@code ratePercent} of this record is informational and never read here.
         *
         * @return a decimal percent ({@code 16} = 16%), or empty when no entry has started by then
         */
        public Optional<BigDecimal> rateAt(Instant instant) {
            Objects.requireNonNull(instant, "instant");
            return rates.stream()
                    .filter(rate -> !rate.effectiveFrom().isAfter(instant))
                    .max(Comparator.comparing(Rate::effectiveFrom))
                    .map(rate -> new BigDecimal(rate.ratePercent()));
        }
    }

    /** One entry of a rate schedule: {@code ratePercent} applies from {@code effectiveFrom} on. */
    public record Rate(String ratePercent, Instant effectiveFrom) {
    }

    /**
     * @param taxCategoryId         its rate at confirmation comes from that category's schedule
     * @param productionDestination KITCHEN, BAR… or {@code null} when nothing is prepared
     * @param price                 the item's price in the store's price list
     * @param kind                  STANDARD, MENU or FEE (v2; a v1 item is STANDARD)
     * @param menuGroups            the component groups of a MENU, ordered by position; empty otherwise (v2)
     */
    public record Item(UUID id, String code, String name, UUID familyId, UUID unitId, UUID taxCategoryId,
                       String productionDestination, boolean soldByWeight, Price price,
                       List<Variant> variants, List<Barcode> barcodes, ItemKind kind, List<MenuGroup> menuGroups) {

        public Item {
            kind = kind == null ? ItemKind.STANDARD : kind;
            menuGroups = menuGroups == null ? List.of() : menuGroups;
        }

        /** A STANDARD item without menu groups (the shape of schema version 1). */
        public Item(UUID id, String code, String name, UUID familyId, UUID unitId, UUID taxCategoryId,
                    String productionDestination, boolean soldByWeight, Price price, List<Variant> variants,
                    List<Barcode> barcodes) {
            this(id, code, name, familyId, unitId, taxCategoryId, productionDestination, soldByWeight, price, variants,
                    barcodes, ItemKind.STANDARD, List.of());
        }

        /**
         * The selling price of this item, or of one of its variants (D58): the variant's own price, falling back
         * to the item price when the variant has none.
         *
         * @param variantId {@code null} for the item itself
         * @throws InvalidCatalogEntryException (422) when {@code variantId} is not a variant of this item
         */
        public Money priceOf(UUID variantId) {
            if (variantId == null) {
                return price.toMoney();
            }
            Variant variant = variants.stream().filter(candidate -> candidate.id().equals(variantId)).findFirst()
                    .orElseThrow(() -> new InvalidCatalogEntryException(
                            "Variant " + variantId + " is not a variant of item " + id));
            return variant.price() == null ? price.toMoney() : variant.price().toMoney();
        }
    }

    /**
     * A component group of a MENU (D108): the order chooses between {@code minChoices} and {@code maxChoices} of its
     * options. Options are ordered by position.
     */
    public record MenuGroup(String code, String name, int minChoices, int maxChoices, List<MenuOption> options) {
    }

    /** {@code variantId} is {@code null} for the item itself; {@code quantity} of it per menu sold. */
    public record MenuOption(UUID itemId, UUID variantId, BigDecimal quantity) {
    }

    /**
     * A POS quick page: ordered entries, each exactly one of an item, a variant or a macro.
     *
     * @param position the page's place among the pages
     */
    public record QuickPage(UUID id, String code, String name, int position, List<QuickPageEntry> entries) {
    }

    /** Exactly one of the three ids is set. */
    public record QuickPageEntry(UUID itemId, UUID variantId, UUID macroId) {
    }

    /** A named list of lines added together as drafts (D109); entries in order. */
    public record Macro(UUID id, String code, String name, List<MacroEntry> entries) {
    }

    public record MacroEntry(UUID itemId, UUID variantId, BigDecimal quantity) {
    }

    /**
     * A line added as a draft when an order opens (D109).
     *
     * @param trigger  {@code TABLE_OPEN_PER_GUEST} (quantity × guests when a table opens) or {@code ORDER_OPEN}
     * @param mode     for {@code ORDER_OPEN}: the order mode it applies to, {@code null} = every mode
     */
    public record AutomaticItem(UUID id, String trigger, String mode, UUID itemId, UUID variantId,
                                BigDecimal quantity) {
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

        public Money toMoney() {
            return Money.of(amount, Currency.getInstance(currency));
        }
    }
}
