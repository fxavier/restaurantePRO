package com.xavier.backend.catalog.service;

import com.xavier.backend.catalog.model.AutomaticItem;
import com.xavier.backend.catalog.model.Barcode;
import com.xavier.backend.catalog.model.Family;
import com.xavier.backend.catalog.model.Item;
import com.xavier.backend.catalog.model.ItemAvailability;
import com.xavier.backend.catalog.model.ItemPrice;
import com.xavier.backend.catalog.model.ItemLine;
import com.xavier.backend.catalog.model.ItemVariant;
import com.xavier.backend.catalog.model.Macro;
import com.xavier.backend.catalog.model.MenuGroup;
import com.xavier.backend.catalog.model.PriceList;
import com.xavier.backend.catalog.model.QuickPage;
import com.xavier.backend.catalog.model.QuickPageEntry;
import com.xavier.backend.catalog.model.StoreItemExclusion;
import com.xavier.backend.catalog.model.TaxCategory;
import com.xavier.backend.catalog.model.TaxRate;
import com.xavier.backend.catalog.model.Unit;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.catalog.model.dto.ExcludedItem;
import com.xavier.backend.catalog.model.dto.ItemKind;
import com.xavier.backend.catalog.model.dto.ReleaseValidationError;
import com.xavier.backend.catalog.model.dto.ReleaseValidationWarning;
import com.xavier.backend.catalog.repository.AutomaticItemRepository;
import com.xavier.backend.catalog.repository.BarcodeRepository;
import com.xavier.backend.catalog.repository.FamilyRepository;
import com.xavier.backend.catalog.repository.ItemPriceRepository;
import com.xavier.backend.catalog.repository.ItemRepository;
import com.xavier.backend.catalog.repository.ItemVariantRepository;
import com.xavier.backend.catalog.repository.MacroRepository;
import com.xavier.backend.catalog.repository.MenuGroupRepository;
import com.xavier.backend.catalog.repository.PriceListRepository;
import com.xavier.backend.catalog.repository.QuickPageRepository;
import com.xavier.backend.catalog.repository.StoreItemExclusionRepository;
import com.xavier.backend.catalog.repository.TaxCategoryRepository;
import com.xavier.backend.catalog.repository.TaxRateRepository;
import com.xavier.backend.catalog.repository.UnitRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Reads the working copy as one store sees it at an instant: what validation checks and what publication
 * freezes. Keeping both in one place means a validated catalog is exactly the catalog that gets published.
 *
 * <p>R4 (D108, D109): the release also freezes the menu groups of its MENU items, every macro, and the quick pages and
 * ACTIVE automatic items of the store and of every store. Whatever they name must be in the release.
 *
 * <p>R5 (D146, D147): the store's excluded items are left out of {@code activeItems}, so they need no price. What
 * company-wide or every-store configuration names an excluded item is dropped from this store's release with a
 * warning (a menu option, a whole macro, a quick page entry, an automatic item); the store's own quick pages and
 * automatic items naming one are errors. With no exclusion the view is exactly R4's (D148).
 */
@Component
class WorkingCopy {

    private final ItemRepository items;
    private final ItemVariantRepository variants;
    private final BarcodeRepository barcodes;
    private final FamilyRepository families;
    private final UnitRepository units;
    private final TaxCategoryRepository taxCategories;
    private final TaxRateRepository taxRates;
    private final PriceListRepository priceLists;
    private final ItemPriceRepository prices;
    private final MenuGroupRepository menuGroups;
    private final MacroRepository macros;
    private final QuickPageRepository quickPages;
    private final AutomaticItemRepository automaticItems;
    private final StoreItemExclusionRepository exclusions;

    WorkingCopy(ItemRepository items, ItemVariantRepository variants, BarcodeRepository barcodes,
                FamilyRepository families, UnitRepository units, TaxCategoryRepository taxCategories,
                TaxRateRepository taxRates, PriceListRepository priceLists, ItemPriceRepository prices,
                MenuGroupRepository menuGroups, MacroRepository macros, QuickPageRepository quickPages,
                AutomaticItemRepository automaticItems, StoreItemExclusionRepository exclusions) {
        this.items = items;
        this.variants = variants;
        this.barcodes = barcodes;
        this.families = families;
        this.units = units;
        this.taxCategories = taxCategories;
        this.taxRates = taxRates;
        this.priceLists = priceLists;
        this.prices = prices;
        this.menuGroups = menuGroups;
        this.macros = macros;
        this.quickPages = quickPages;
        this.automaticItems = automaticItems;
        this.exclusions = exclusions;
    }

    /** The store's view of the working copy at {@code at}. */
    View of(UUID storeId, Instant at) {
        Set<UUID> excludedIds = exclusions.findAllByIdStoreId(storeId).stream().map(StoreItemExclusion::getItemId)
                .collect(Collectors.toSet());
        Map<Boolean, List<Item>> byExclusion = items.findAllByAvailabilityOrderByCode(ItemAvailability.ACTIVE)
                .stream().collect(Collectors.partitioningBy(item -> excludedIds.contains(item.getId())));
        List<Item> active = byExclusion.get(false);
        Set<UUID> categoryIds = active.stream().map(Item::getTaxCategoryId).collect(Collectors.toSet());
        Map<UUID, List<TaxRate>> ratesByCategory = taxRates.findAllByTaxCategoryIdIn(categoryIds).stream()
                .collect(Collectors.groupingBy(TaxRate::getTaxCategoryId));
        Map<UUID, TaxRate> rateInEffect = ratesByCategory.entrySet().stream()
                .flatMap(entry -> TaxRate.inEffectAt(entry.getValue(), at).stream())
                .collect(Collectors.toMap(TaxRate::getTaxCategoryId, Function.identity()));
        Optional<PriceList> priceList = priceLists.findByStoreId(storeId);
        Map<PriceKey, ItemPrice> pricesByKey = priceList.map(list -> prices.findAllByPriceListId(list.getId())).orElse(List.of())
                .stream().collect(Collectors.toMap(price -> new PriceKey(price.getItemId(), price.getVariantId()),
                        Function.identity()));
        Map<UUID, List<MenuGroup>> groupsByItem = menuGroups.findAllByItemIdIn(
                        active.stream().map(Item::getId).toList()).stream()
                .sorted(Comparator.comparing(MenuGroup::getPosition).thenComparing(MenuGroup::getCode))
                .collect(Collectors.groupingBy(MenuGroup::getItemId));
        List<AutomaticItem> automatic = automaticItems.findAllForStore(storeId).stream()
                .filter(AutomaticItem::isActive).toList();
        List<QuickPage> pages = quickPages.findAllForStore(storeId);
        Map<UUID, UUID> itemOfVariant = variants.findAllById(pages.stream()
                        .flatMap(page -> page.getEntries().stream()).map(QuickPageEntry::getVariantId)
                        .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ItemVariant::getId, ItemVariant::getItemId));
        return new View(storeId, at, priceList, active, byExclusion.get(true), ratesByCategory, rateInEffect,
                pricesByKey, new Composition(groupsByItem, macros.findAllByOrderByCode(), pages, itemOfVariant,
                automatic));
    }

    /**
     * The R4 configuration that composes lines: menu groups of the active items, macros, quick pages (with the item of
     * each variant they name), automatic items.
     */
    private record Composition(Map<UUID, List<MenuGroup>> groupsByItem, List<Macro> macros, List<QuickPage> quickPages,
                               Map<UUID, UUID> itemOfVariant, List<AutomaticItem> automaticItems) {
    }

    private record PriceKey(UUID itemId, UUID variantId) {
    }

    final class View {

        private final UUID storeId;
        private final Instant at;
        private final Optional<PriceList> priceList;
        private final List<Item> activeItems;
        private final Map<UUID, Item> excludedById;
        private final Map<UUID, Item> firstExcludedOfOmittedMacro;
        private final Map<UUID, List<TaxRate>> ratesByCategory;
        private final Map<UUID, TaxRate> rateInEffect;
        private final Map<PriceKey, ItemPrice> prices;
        private final Composition composition;
        private final Map<UUID, Item> activeById;

        private View(UUID storeId, Instant at, Optional<PriceList> priceList, List<Item> activeItems,
                     List<Item> excludedItems, Map<UUID, List<TaxRate>> ratesByCategory,
                     Map<UUID, TaxRate> rateInEffect, Map<PriceKey, ItemPrice> prices, Composition composition) {
            this.storeId = storeId;
            this.at = at;
            this.priceList = priceList;
            this.activeItems = activeItems;
            this.excludedById = excludedItems.stream().collect(Collectors.toMap(Item::getId, Function.identity()));
            this.firstExcludedOfOmittedMacro = new HashMap<>();
            for (Macro macro : composition.macros()) {
                macro.getEntries().stream().map(ItemLine::getItemId).filter(excludedById::containsKey).findFirst()
                        .ifPresent(itemId -> firstExcludedOfOmittedMacro.put(macro.getId(), excludedById.get(itemId)));
            }
            this.ratesByCategory = ratesByCategory;
            this.rateInEffect = rateInEffect;
            this.prices = prices;
            this.composition = composition;
            this.activeById = activeItems.stream().collect(Collectors.toMap(Item::getId, Function.identity()));
        }

        /**
         * Every active item needs a price in the store's price list and a tax rate in effect. Only a MENU has menu
         * groups, its groups' bounds can be met, and its options are active items of the release that are not
         * menus; macro entries, quick page entries and automatic items name items of the release, and macro entries
         * and automatic items do not name a MENU.
         */
        List<ReleaseValidationError> errors() {
            if (priceList.isEmpty()) {
                return List.of(new ReleaseValidationError("STORE_WITHOUT_PRICE_LIST", null, null,
                        "Store " + storeId + " has no price list"));
            }
            List<ReleaseValidationError> errors = new ArrayList<>();
            for (Item item : activeItems) {
                if (!prices.containsKey(new PriceKey(item.getId(), null))) {
                    errors.add(new ReleaseValidationError("ITEM_WITHOUT_PRICE", item.getId(), item.getCode(),
                            "Item " + item.getCode() + " has no price in this store's price list: price it, or "
                                    + "exclude it from this store"));
                }
                if (!rateInEffect.containsKey(item.getTaxCategoryId())) {
                    errors.add(new ReleaseValidationError("ITEM_WITHOUT_TAX_RATE", item.getId(), item.getCode(),
                            "Item " + item.getCode() + " has a tax category without a rate in effect at " + at));
                }
                menuErrors(item, errors);
            }
            compositionErrors(errors);
            return errors;
        }

        private void menuErrors(Item item, List<ReleaseValidationError> errors) {
            List<MenuGroup> groups = composition.groupsByItem().getOrDefault(item.getId(), List.of());
            if (groups.isEmpty()) {
                return;
            }
            if (item.getKind() != ItemKind.MENU) {
                errors.add(new ReleaseValidationError("MENU_GROUPS_ON_NON_MENU", item.getId(), item.getCode(),
                        "Item " + item.getCode() + " is " + item.getKind() + " but has menu groups"));
                return;
            }
            for (MenuGroup group : groups) {
                if (!group.boundsHold()) {
                    errors.add(new ReleaseValidationError("MENU_GROUP_BOUNDS_INVALID", item.getId(), item.getCode(),
                            "Menu group " + group.getCode() + " asks for " + group.getMinChoices() + ".."
                                    + group.getMaxChoices() + " of " + group.getOptions().size() + " options"));
                } else if (keptOptions(group).size() < group.getMinChoices()) {
                    errors.add(new ReleaseValidationError("MENU_GROUP_BOUNDS_INVALID", item.getId(), item.getCode(),
                            "Menu group " + group.getCode() + " asks for at least " + group.getMinChoices() + " of "
                                    + keptOptions(group).size() + " options left once this store's exclusions are "
                                    + "dropped"));
                }
                for (ItemLine option : keptOptions(group)) {
                    Item chosen = activeById.get(option.getItemId());
                    if (chosen == null) {
                        errors.add(new ReleaseValidationError("MENU_OPTION_NOT_IN_RELEASE", item.getId(),
                                item.getCode(), "Option " + option.getItemId() + " of menu group " + group.getCode()
                                + " is not an active item"));
                    } else if (chosen.getKind() == ItemKind.MENU) {
                        errors.add(new ReleaseValidationError("MENU_OPTION_IS_MENU", item.getId(), item.getCode(),
                                "Option " + chosen.getCode() + " of menu group " + group.getCode() + " is a MENU"));
                    }
                }
            }
        }

        private void compositionErrors(List<ReleaseValidationError> errors) {
            for (Macro macro : keptMacros()) {
                macro.getEntries().stream().map(ItemLine::getItemId).forEach(id -> referenceError(errors, id,
                        "MACRO_ENTRY_NOT_IN_RELEASE", "MACRO_ENTRY_IS_MENU", "Macro " + macro.getCode()));
            }
            for (QuickPage page : composition.quickPages()) {
                for (QuickPageEntry entry : page.getEntries()) {
                    Item excluded = exclusionOf(entry);
                    if (excluded != null) {
                        if (page.getStoreId() != null) {
                            errors.add(new ReleaseValidationError("QUICK_PAGE_ENTRY_EXCLUDED", excluded.getId(),
                                    excluded.getCode(), "Quick page " + page.getCode() + " of this store names item "
                                    + excluded.getCode() + ", which this store excludes"));
                        }
                        continue;
                    }
                    Stream.ofNullable(itemOf(entry)).filter(id -> !activeById.containsKey(id))
                            .forEach(id -> errors.add(new ReleaseValidationError("QUICK_PAGE_ENTRY_NOT_IN_RELEASE", id,
                                    null, "Quick page " + page.getCode() + " names item " + id
                                    + ", which is not active")));
                }
            }
            for (AutomaticItem automatic : composition.automaticItems()) {
                Item excluded = excludedById.get(automatic.getLine().getItemId());
                if (excluded == null) {
                    referenceError(errors, automatic.getLine().getItemId(), "AUTOMATIC_ITEM_NOT_IN_RELEASE",
                            "AUTOMATIC_ITEM_IS_MENU", "An automatic item");
                } else if (automatic.getStoreId() != null) {
                    errors.add(new ReleaseValidationError("AUTOMATIC_ITEM_EXCLUDED", excluded.getId(),
                            excluded.getCode(), "An automatic item of this store names item " + excluded.getCode()
                            + ", which this store excludes"));
                }
            }
        }

        /** What this store's exclusions leave out of its release (D147). They never block publication. */
        List<ReleaseValidationWarning> warnings() {
            List<ReleaseValidationWarning> warnings = new ArrayList<>();
            for (Item menu : activeItems) {
                if (menu.getKind() != ItemKind.MENU) {
                    continue;
                }
                for (MenuGroup group : composition.groupsByItem().getOrDefault(menu.getId(), List.of())) {
                    group.getOptions().stream().map(option -> excludedById.get(option.getItemId()))
                            .filter(Objects::nonNull)
                            .forEach(excluded -> warnings.add(warning("MENU_OPTION_EXCLUDED", excluded,
                                    "Option " + excluded.getCode() + " of menu group " + group.getCode() + " of menu "
                                            + menu.getCode() + " is dropped")));
                }
            }
            for (Macro macro : composition.macros()) {
                Item excluded = firstExcludedOfOmittedMacro.get(macro.getId());
                if (excluded != null) {
                    warnings.add(warning("MACRO_OMITTED", excluded, "Macro " + macro.getCode()
                            + " is omitted: it names item " + excluded.getCode()));
                }
            }
            for (QuickPage page : composition.quickPages()) {
                if (page.getStoreId() != null) {
                    continue; // the store's own page naming an excluded item is an error
                }
                page.getEntries().stream().map(this::exclusionOf).filter(Objects::nonNull)
                        .forEach(excluded -> warnings.add(warning("QUICK_PAGE_ENTRY_OMITTED", excluded,
                                "An entry of quick page " + page.getCode() + " is omitted: it needs item "
                                        + excluded.getCode())));
            }
            composition.automaticItems().stream()
                    .filter(automatic -> automatic.getStoreId() == null)
                    .map(automatic -> excludedById.get(automatic.getLine().getItemId())).filter(Objects::nonNull)
                    .forEach(excluded -> warnings.add(warning("AUTOMATIC_ITEM_OMITTED", excluded,
                            "An automatic item of every store naming item " + excluded.getCode() + " is omitted")));
            return warnings;
        }

        /** The active items the store excludes, by code (D146). */
        List<ExcludedItem> excluded() {
            return excludedById.values().stream().sorted(Comparator.comparing(Item::getCode))
                    .map(item -> new ExcludedItem(item.getId(), item.getCode(), item.getName())).toList();
        }

        private static ReleaseValidationWarning warning(String code, Item excluded, String message) {
            return new ReleaseValidationWarning(code, excluded.getId(), excluded.getCode(),
                    message + " (item excluded from this store)");
        }

        private List<ItemLine> keptOptions(MenuGroup group) {
            return group.getOptions().stream().filter(option -> !excludedById.containsKey(option.getItemId())).toList();
        }

        private List<Macro> keptMacros() {
            return composition.macros().stream()
                    .filter(macro -> !firstExcludedOfOmittedMacro.containsKey(macro.getId())).toList();
        }

        /** The item a quick page entry names, directly or through its variant; {@code null} for a macro entry. */
        private UUID itemOf(QuickPageEntry entry) {
            return entry.getVariantId() == null ? entry.getItemId()
                    : composition.itemOfVariant().get(entry.getVariantId());
        }

        /** The excluded item that keeps this entry out of the store's release, itself or in its macro; else null. */
        private Item exclusionOf(QuickPageEntry entry) {
            if (entry.getMacroId() != null) {
                return firstExcludedOfOmittedMacro.get(entry.getMacroId());
            }
            UUID itemId = itemOf(entry);
            return itemId == null ? null : excludedById.get(itemId);
        }

        /** A macro entry or an automatic item adds a draft: its item is in the release and is not a MENU. */
        private void referenceError(List<ReleaseValidationError> errors, UUID itemId, String notInRelease,
                                    String isMenu, String referrer) {
            Item item = activeById.get(itemId);
            if (item == null) {
                errors.add(new ReleaseValidationError(notInRelease, itemId, null,
                        referrer + " names item " + itemId + ", which is not active"));
            } else if (item.getKind() == ItemKind.MENU) {
                errors.add(new ReleaseValidationError(isMenu, itemId, item.getCode(),
                        referrer + " names MENU item " + item.getCode() + ", which needs component choices"));
            }
        }

        /** The complete store catalog to freeze. Call only when {@link #errors()} is empty. */
        CatalogSnapshot snapshot(UUID releaseId, long version) {
            PriceList list = priceList.orElseThrow();
            List<UUID> itemIds = activeItems.stream().map(Item::getId).toList();
            Map<UUID, List<ItemVariant>> variantsByItem = variants.findAllByItemIdIn(itemIds).stream()
                    .sorted(Comparator.comparing(ItemVariant::getCode))
                    .collect(Collectors.groupingBy(ItemVariant::getItemId));
            Set<UUID> loadedVariants = variantsByItem.values().stream().flatMap(List::stream)
                    .map(ItemVariant::getId).collect(Collectors.toSet());
            // separate READ COMMITTED reads: a barcode may point at a variant created after the variants were read
            Map<UUID, List<Barcode>> barcodesByItem = barcodes.findAllByItemIdIn(itemIds).stream()
                    .filter(barcode -> barcode.getVariantId() == null || loadedVariants.contains(barcode.getVariantId()))
                    .sorted(Comparator.comparing(Barcode::getCode))
                    .collect(Collectors.groupingBy(Barcode::getItemId));
            return new CatalogSnapshot(CatalogSnapshot.SCHEMA_VERSION, storeId, releaseId, version, at,
                    list.getCurrency().getCurrencyCode(), families(), units(), taxCategories(),
                    activeItems.stream().map(item -> item(item, variantsByItem.getOrDefault(item.getId(), List.of()),
                            barcodesByItem.getOrDefault(item.getId(), List.of()))).toList(),
                    composition.quickPages().stream().map(this::quickPage).toList(),
                    keptMacros().stream().map(View::macro).toList(),
                    composition.automaticItems().stream()
                            .filter(automatic -> !excludedById.containsKey(automatic.getLine().getItemId()))
                            .map(View::automaticItem).toList());
        }

        private CatalogSnapshot.QuickPage quickPage(QuickPage page) {
            return new CatalogSnapshot.QuickPage(page.getId(), page.getCode(), page.getName(), page.getPosition(),
                    page.getEntries().stream().filter(entry -> exclusionOf(entry) == null).map(entry -> new CatalogSnapshot.QuickPageEntry(entry.getItemId(),
                            entry.getVariantId(), entry.getMacroId())).toList());
        }

        private static CatalogSnapshot.Macro macro(Macro macro) {
            return new CatalogSnapshot.Macro(macro.getId(), macro.getCode(), macro.getName(), macro.getEntries().stream()
                    .map(entry -> new CatalogSnapshot.MacroEntry(entry.getItemId(), entry.getVariantId(),
                            entry.getQuantity())).toList());
        }

        private static CatalogSnapshot.AutomaticItem automaticItem(AutomaticItem automatic) {
            ItemLine line = automatic.getLine();
            return new CatalogSnapshot.AutomaticItem(automatic.getId(), automatic.getTrigger().name(),
                    automatic.getMode(), line.getItemId(), line.getVariantId(), line.getQuantity());
        }

        private List<CatalogSnapshot.MenuGroup> menuGroups(Item item) {
            if (item.getKind() != ItemKind.MENU) {
                return List.of();
            }
            return composition.groupsByItem().getOrDefault(item.getId(), List.of()).stream()
                    .map(group -> new CatalogSnapshot.MenuGroup(group.getCode(), group.getName(), group.getMinChoices(),
                            group.getMaxChoices(), keptOptions(group).stream()
                            .map(option -> new CatalogSnapshot.MenuOption(option.getItemId(), option.getVariantId(),
                                    option.getQuantity())).toList()))
                    .toList();
        }

        private CatalogSnapshot.Item item(Item item, List<ItemVariant> itemVariants, List<Barcode> itemBarcodes) {
            return new CatalogSnapshot.Item(item.getId(), item.getCode(), item.getName(), item.getFamilyId(),
                    item.getUnitId(), item.getTaxCategoryId(), item.getProductionDestination(), item.isSoldByWeight(), price(item.getId(), null),
                    itemVariants.stream().map(variant -> new CatalogSnapshot.Variant(variant.getId(), variant.getCode(),
                            variant.getName(), price(item.getId(), variant.getId()))).toList(),
                    itemBarcodes.stream().map(barcode -> new CatalogSnapshot.Barcode(barcode.getCode(),
                            barcode.getVariantId())).toList(), item.getKind(), menuGroups(item));
        }

        private CatalogSnapshot.Price price(UUID itemId, UUID variantId) {
            ItemPrice price = prices.get(new PriceKey(itemId, variantId));
            return price == null ? null : CatalogSnapshot.Price.of(price.getPrice());
        }

        private List<CatalogSnapshot.Rate> schedule(UUID categoryId) {
            return ratesByCategory.get(categoryId).stream()
                    .sorted(Comparator.comparing(TaxRate::getEffectiveFrom))
                    .map(rate -> new CatalogSnapshot.Rate(rate.getRatePercent().toPlainString(), rate.getEffectiveFrom()))
                    .toList();
        }

        private List<CatalogSnapshot.Family> families() {
            return families.findAll(Sort.by("code")).stream().map(Family::toSummary)
                    .map(f -> new CatalogSnapshot.Family(f.id(), f.code(), f.name(), f.parentId())).toList();
        }

        private List<CatalogSnapshot.Unit> units() {
            return units.findAll(Sort.by("code")).stream()
                    .map(unit -> new CatalogSnapshot.Unit(unit.getId(), unit.getCode(), unit.getName())).toList();
        }

        private List<CatalogSnapshot.TaxCategory> taxCategories() {
            return taxCategories.findAllById(rateInEffect.keySet()).stream()
                    .sorted(Comparator.comparing(TaxCategory::getCode))
                    .map(category -> {
                        TaxRate rate = rateInEffect.get(category.getId());
                        return new CatalogSnapshot.TaxCategory(category.getId(), category.getCode(), category.getName(),
                                rate.getRatePercent().toPlainString(), rate.getEffectiveFrom(),
                                schedule(category.getId()));
                    }).toList();
        }
    }
}
