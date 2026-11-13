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
import com.xavier.backend.catalog.model.TaxCategory;
import com.xavier.backend.catalog.model.TaxRate;
import com.xavier.backend.catalog.model.Unit;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.catalog.model.dto.ItemKind;
import com.xavier.backend.catalog.model.dto.ReleaseValidationError;
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
import com.xavier.backend.catalog.repository.TaxCategoryRepository;
import com.xavier.backend.catalog.repository.TaxRateRepository;
import com.xavier.backend.catalog.repository.UnitRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

/**
 * TEST ONLY: the R4 working-copy reader, verbatim as of 12885fc (before store exclusions), kept as the reference
 * {@code NoExclusionSnapshotUnchangedTest} compares the current snapshot with (D148). Never edit it.
 *
 * <p>Reads the working copy as one store sees it at an instant: what validation checks and what publication
 * freezes. Keeping both in one place means a validated catalog is exactly the catalog that gets published.
 *
 * <p>R4 (D108, D109): the release also freezes the menu groups of its MENU items, every macro, and the quick pages and
 * ACTIVE automatic items of the store and of every store. Whatever they name must be in the release.
 */
class R4WorkingCopy {

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

    R4WorkingCopy(ItemRepository items, ItemVariantRepository variants, BarcodeRepository barcodes,
                FamilyRepository families, UnitRepository units, TaxCategoryRepository taxCategories,
                TaxRateRepository taxRates, PriceListRepository priceLists, ItemPriceRepository prices,
                MenuGroupRepository menuGroups, MacroRepository macros, QuickPageRepository quickPages,
                AutomaticItemRepository automaticItems) {
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
    }

    /** The store's view of the working copy at {@code at}. */
    View of(UUID storeId, Instant at) {
        List<Item> active = items.findAllByAvailabilityOrderByCode(ItemAvailability.ACTIVE);
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
        return new View(storeId, at, priceList, active, ratesByCategory, rateInEffect, pricesByKey,
                new Composition(groupsByItem, macros.findAllByOrderByCode(), quickPages.findAllForStore(storeId),
                        automatic));
    }

    /** The R4 configuration that composes lines: menu groups of the active items, macros, quick pages, automatic items. */
    private record Composition(Map<UUID, List<MenuGroup>> groupsByItem, List<Macro> macros, List<QuickPage> quickPages,
                               List<AutomaticItem> automaticItems) {
    }

    private record PriceKey(UUID itemId, UUID variantId) {
    }

    final class View {

        private final UUID storeId;
        private final Instant at;
        private final Optional<PriceList> priceList;
        private final List<Item> activeItems;
        private final Map<UUID, List<TaxRate>> ratesByCategory;
        private final Map<UUID, TaxRate> rateInEffect;
        private final Map<PriceKey, ItemPrice> prices;
        private final Composition composition;
        private final Map<UUID, Item> activeById;

        private View(UUID storeId, Instant at, Optional<PriceList> priceList, List<Item> activeItems,
                     Map<UUID, List<TaxRate>> ratesByCategory, Map<UUID, TaxRate> rateInEffect,
                     Map<PriceKey, ItemPrice> prices, Composition composition) {
            this.storeId = storeId;
            this.at = at;
            this.priceList = priceList;
            this.activeItems = activeItems;
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
                            "Item " + item.getCode() + " has no price in this store's price list"));
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
                }
                for (ItemLine option : group.getOptions()) {
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
            for (Macro macro : composition.macros()) {
                macro.getEntries().stream().map(ItemLine::getItemId).forEach(id -> referenceError(errors, id,
                        "MACRO_ENTRY_NOT_IN_RELEASE", "MACRO_ENTRY_IS_MENU", "Macro " + macro.getCode()));
            }
            Map<UUID, UUID> itemOfVariant = variants.findAllById(composition.quickPages().stream()
                            .flatMap(page -> page.getEntries().stream()).map(QuickPageEntry::getVariantId)
                            .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                    .collect(Collectors.toMap(ItemVariant::getId, ItemVariant::getItemId));
            for (QuickPage page : composition.quickPages()) {
                page.getEntries().stream()
                        .flatMap(entry -> Stream.ofNullable(entry.getVariantId() == null ? entry.getItemId()
                                : itemOfVariant.get(entry.getVariantId())))
                        .filter(id -> !activeById.containsKey(id))
                        .forEach(id -> errors.add(new ReleaseValidationError("QUICK_PAGE_ENTRY_NOT_IN_RELEASE", id,
                                null, "Quick page " + page.getCode() + " names item " + id + ", which is not active")));
            }
            composition.automaticItems().stream().map(automatic -> automatic.getLine().getItemId())
                    .forEach(id -> referenceError(errors, id, "AUTOMATIC_ITEM_NOT_IN_RELEASE",
                            "AUTOMATIC_ITEM_IS_MENU", "An automatic item"));
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
                    composition.quickPages().stream().map(View::quickPage).toList(),
                    composition.macros().stream().map(View::macro).toList(),
                    composition.automaticItems().stream().map(View::automaticItem).toList());
        }

        private static CatalogSnapshot.QuickPage quickPage(QuickPage page) {
            return new CatalogSnapshot.QuickPage(page.getId(), page.getCode(), page.getName(), page.getPosition(),
                    page.getEntries().stream().map(entry -> new CatalogSnapshot.QuickPageEntry(entry.getItemId(),
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
                            group.getMaxChoices(), group.getOptions().stream()
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
