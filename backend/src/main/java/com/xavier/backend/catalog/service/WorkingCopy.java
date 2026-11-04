package com.xavier.backend.catalog.service;

import com.xavier.backend.catalog.model.Barcode;
import com.xavier.backend.catalog.model.Family;
import com.xavier.backend.catalog.model.Item;
import com.xavier.backend.catalog.model.ItemAvailability;
import com.xavier.backend.catalog.model.ItemPrice;
import com.xavier.backend.catalog.model.ItemVariant;
import com.xavier.backend.catalog.model.PriceList;
import com.xavier.backend.catalog.model.TaxCategory;
import com.xavier.backend.catalog.model.TaxRate;
import com.xavier.backend.catalog.model.Unit;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.catalog.model.dto.ReleaseValidationError;
import com.xavier.backend.catalog.repository.BarcodeRepository;
import com.xavier.backend.catalog.repository.FamilyRepository;
import com.xavier.backend.catalog.repository.ItemPriceRepository;
import com.xavier.backend.catalog.repository.ItemRepository;
import com.xavier.backend.catalog.repository.ItemVariantRepository;
import com.xavier.backend.catalog.repository.PriceListRepository;
import com.xavier.backend.catalog.repository.TaxCategoryRepository;
import com.xavier.backend.catalog.repository.TaxRateRepository;
import com.xavier.backend.catalog.repository.UnitRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Reads the working copy as one store sees it at an instant: what validation checks and what publication
 * freezes. Keeping both in one place means a validated catalog is exactly the catalog that gets published.
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

    WorkingCopy(ItemRepository items, ItemVariantRepository variants, BarcodeRepository barcodes,
                FamilyRepository families, UnitRepository units, TaxCategoryRepository taxCategories,
                TaxRateRepository taxRates, PriceListRepository priceLists, ItemPriceRepository prices) {
        this.items = items;
        this.variants = variants;
        this.barcodes = barcodes;
        this.families = families;
        this.units = units;
        this.taxCategories = taxCategories;
        this.taxRates = taxRates;
        this.priceLists = priceLists;
        this.prices = prices;
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
        return new View(storeId, at, priceList, active, ratesByCategory, rateInEffect, pricesByKey);
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

        private View(UUID storeId, Instant at, Optional<PriceList> priceList, List<Item> activeItems,
                     Map<UUID, List<TaxRate>> ratesByCategory, Map<UUID, TaxRate> rateInEffect,
                     Map<PriceKey, ItemPrice> prices) {
            this.storeId = storeId;
            this.at = at;
            this.priceList = priceList;
            this.activeItems = activeItems;
            this.ratesByCategory = ratesByCategory;
            this.rateInEffect = rateInEffect;
            this.prices = prices;
        }

        /** Every active item needs a price in the store's price list and a tax rate in effect. */
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
            }
            return errors;
        }

        /** The complete store catalog to freeze. Call only when {@link #errors()} is empty. */
        CatalogSnapshot snapshot(UUID releaseId, long version) {
            PriceList list = priceList.orElseThrow();
            List<UUID> itemIds = activeItems.stream().map(Item::getId).toList();
            Map<UUID, List<ItemVariant>> variantsByItem = variants.findAllByItemIdIn(itemIds).stream()
                    .sorted(Comparator.comparing(ItemVariant::getCode))
                    .collect(Collectors.groupingBy(ItemVariant::getItemId));
            Map<UUID, List<Barcode>> barcodesByItem = barcodes.findAllByItemIdIn(itemIds).stream()
                    .sorted(Comparator.comparing(Barcode::getCode))
                    .collect(Collectors.groupingBy(Barcode::getItemId));
            return new CatalogSnapshot(CatalogSnapshot.SCHEMA_VERSION, storeId, releaseId, version, at,
                    list.getCurrency().getCurrencyCode(), families(), units(), taxCategories(),
                    activeItems.stream().map(item -> item(item, variantsByItem.getOrDefault(item.getId(), List.of()),
                            barcodesByItem.getOrDefault(item.getId(), List.of()))).toList());
        }

        private CatalogSnapshot.Item item(Item item, List<ItemVariant> itemVariants, List<Barcode> itemBarcodes) {
            return new CatalogSnapshot.Item(item.getId(), item.getCode(), item.getName(), item.getFamilyId(),
                    item.getUnitId(), item.getTaxCategoryId(), item.getProductionDestination(), item.isSoldByWeight(), price(item.getId(), null),
                    itemVariants.stream().map(variant -> new CatalogSnapshot.Variant(variant.getId(), variant.getCode(),
                            variant.getName(), price(item.getId(), variant.getId()))).toList(),
                    itemBarcodes.stream().map(barcode -> new CatalogSnapshot.Barcode(barcode.getCode(),
                            barcode.getVariantId())).toList());
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
