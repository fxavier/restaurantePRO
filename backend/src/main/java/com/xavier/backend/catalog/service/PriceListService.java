package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.exception.CatalogCodeTakenException;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.model.ItemPrice;
import com.xavier.backend.catalog.model.PriceList;
import com.xavier.backend.catalog.model.dto.ItemPriceCreateRequest;
import com.xavier.backend.catalog.model.dto.ItemPriceSummary;
import com.xavier.backend.catalog.model.dto.ItemPriceUpdateRequest;
import com.xavier.backend.catalog.model.dto.PriceListCreateRequest;
import com.xavier.backend.catalog.model.dto.PriceListSummary;
import com.xavier.backend.catalog.model.dto.PriceListUpdateRequest;
import com.xavier.backend.catalog.repository.ItemPriceRepository;
import com.xavier.backend.catalog.repository.ItemRepository;
import com.xavier.backend.catalog.repository.ItemVariantRepository;
import com.xavier.backend.catalog.repository.PriceListRepository;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.StoreProbe;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A store's price list and its item prices: {@code CATALOG_MANAGE} in that store. */
@Service
public class PriceListService {

    private final PriceListRepository priceLists;
    private final ItemPriceRepository prices;
    private final ItemRepository items;
    private final ItemVariantRepository variants;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final StoreProbe probe;
    private final CatalogAudit audit;
    private final Clock clock;

    PriceListService(PriceListRepository priceLists, ItemPriceRepository prices, ItemRepository items,
                     ItemVariantRepository variants, OrganizationApi organization, AccessPolicy accessPolicy,
                     StoreProbe probe, CatalogAudit audit, Clock clock) {
        this.priceLists = priceLists;
        this.prices = prices;
        this.items = items;
        this.variants = variants;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.probe = probe;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public PriceListSummary create(PriceListCreateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE, request.storeId());
        StoreSummary store = organization.findStore(request.storeId())
                .orElseThrow(() -> new InvalidCatalogEntryException("Store " + request.storeId() + " does not exist"));
        PriceList list = UniqueConstraints.saveAndFlush(priceLists,
                PriceList.create(store.id(), request.name(), store.currency(), clock.instant()),
                () -> new CatalogCodeTakenException("A price list for store " + store.code()), "price_list_store_uk");
        audit.record("PRICE_LIST_CREATED", "PriceList", list.getId(), list.getStoreId(), Map.of());
        return list.toSummary();
    }

    @Transactional
    public PriceListSummary rename(UUID id, PriceListUpdateRequest request) {
        PriceList list = authorizedPriceList(id);
        OptimisticLocking.requireVersion(PriceList.class, id, list.getVersion(), request.version());
        list.rename(request.name(), clock.instant());
        priceLists.saveAndFlush(list);
        audit.record("PRICE_LIST_UPDATED", "PriceList", id, list.getStoreId(), Map.of());
        return list.toSummary();
    }

    @Transactional(readOnly = true)
    public PriceListSummary get(UUID id) {
        PriceList list = authorizedPriceList(id);
        return list.toSummary();
    }

    @Transactional(readOnly = true)
    public List<PriceListSummary> listForStore(UUID storeId) {
        accessPolicy.require(Permission.CATALOG_MANAGE, storeId);
        return priceLists.findAllByStoreId(storeId).stream().map(PriceList::toSummary).toList();
    }

    @Transactional
    public ItemPriceSummary addPrice(UUID priceListId, ItemPriceCreateRequest request) {
        PriceList list = authorizedPriceList(priceListId);
        requireTarget(request.itemId(), request.variantId());
        ItemPrice price = UniqueConstraints.saveAndFlush(prices,
                ItemPrice.create(list.getId(), request.itemId(), request.variantId(),
                        money(request.amount(), list), clock.instant()),
                () -> new CatalogCodeTakenException("A price for this item in this list"),
                "item_price_list_item_variant_uk");
        auditPrice(price, list);
        return price.toSummary();
    }

    @Transactional
    public ItemPriceSummary changePrice(UUID priceListId, UUID priceId, ItemPriceUpdateRequest request) {
        PriceList list = authorizedPriceList(priceListId);
        ItemPrice price = prices.findByIdAndPriceListId(priceId, priceListId)
                .orElseThrow(() -> new CatalogEntryNotFoundException("Item price", priceId));
        OptimisticLocking.requireVersion(ItemPrice.class, priceId, price.getVersion(), request.version());
        price.change(money(request.amount(), list), clock.instant());
        prices.saveAndFlush(price);
        auditPrice(price, list);
        return price.toSummary();
    }

    @Transactional(readOnly = true)
    public List<ItemPriceSummary> listPrices(UUID priceListId) {
        PriceList list = authorizedPriceList(priceListId);
        return prices.findAllByPriceListId(priceListId).stream().map(ItemPrice::toSummary).toList();
    }

    private void requireTarget(UUID itemId, UUID variantId) {
        if (!items.existsById(itemId)) {
            throw new InvalidCatalogEntryException("Item " + itemId + " does not exist");
        }
        if (variantId != null && !variants.existsByIdAndItemId(variantId, itemId)) {
            throw new InvalidCatalogEntryException("Variant " + variantId + " is not a variant of item " + itemId);
        }
    }

    /** An amount finer than the currency's minor unit is refused, never rounded (D15). */
    private static Money money(BigDecimal amount, PriceList list) {
        try {
            return Money.of(amount, list.getCurrency());
        } catch (IllegalArgumentException e) {
            throw new InvalidCatalogEntryException(e.getMessage());
        }
    }

    /**
     * D42 (b): the list's store alone (absent: 404), then {@code CATALOG_MANAGE} there (403), then the list. The store
     * is probed through {@code shared.store_of}, which sees every store: row-level security hides another store's list
     * from a plain read, which would turn the 403 into a 404.
     */
    private PriceList authorizedPriceList(UUID id) {
        UUID storeId = probe.storeOf("catalog.price_list", id).orElseThrow(() -> notFound(id));
        accessPolicy.require(Permission.CATALOG_MANAGE, storeId);
        return priceLists.findById(id).orElseThrow(() -> notFound(id));
    }

    private static CatalogEntryNotFoundException notFound(UUID id) {
        return new CatalogEntryNotFoundException("Price list", id);
    }

    private void auditPrice(ItemPrice price, PriceList list) {
        Map<String, Object> details = new HashMap<>();
        details.put("itemId", price.getItemId().toString());
        if (price.getVariantId() != null) {
            details.put("variantId", price.getVariantId().toString());
        }
        details.put("amount", price.getPrice().amount().toPlainString());
        details.put("currency", price.getPrice().currency().getCurrencyCode());
        audit.record("ITEM_PRICE_SET", "ItemPrice", price.getId(), list.getStoreId(), details);
    }
}
