package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.model.Item;
import com.xavier.backend.catalog.model.StoreItemExclusion;
import com.xavier.backend.catalog.model.dto.StoreItemExclusionRequest;
import com.xavier.backend.catalog.model.dto.StoreItemExclusionView;
import com.xavier.backend.catalog.repository.ItemRepository;
import com.xavier.backend.catalog.repository.StoreItemExclusionRepository;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-store exclusions (D146): configuration of one store's working copy, so reading needs {@code CATALOG_VIEW} and
 * writing {@code CATALOG_MANAGE} in that store (or installation-wide). A change takes effect at the store's next
 * publication; every real change is audited, and repeating the same request changes nothing.
 */
@Service
public class StoreItemExclusionService {

    private final StoreItemExclusionRepository exclusions;
    private final ItemRepository items;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final CatalogAudit audit;
    private final Clock clock;

    StoreItemExclusionService(StoreItemExclusionRepository exclusions, ItemRepository items,
                              OrganizationApi organization, AccessPolicy accessPolicy, CatalogAudit audit,
                              Clock clock) {
        this.exclusions = exclusions;
        this.items = items;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<StoreItemExclusionView> list(UUID storeId, Pageable pageable) {
        accessPolicy.require(Permission.CATALOG_VIEW, storeId);
        Page<StoreItemExclusion> page = exclusions.findAllByIdStoreId(storeId, pageable);
        Map<UUID, Item> byId = items.findAllById(page.map(StoreItemExclusion::getItemId).toList()).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
        return PageResponse.from(page.map(exclusion -> view(exclusion, byId.get(exclusion.getItemId()))));
    }

    /**
     * Excludes the item from the store, or sets the reason of an existing exclusion.
     *
     * @throws CatalogEntryNotFoundException 404 {@code STORE_NOT_FOUND} or {@code ITEM_NOT_FOUND}
     */
    @Transactional
    public StoreItemExclusionView exclude(UUID storeId, UUID itemId, StoreItemExclusionRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE, storeId);
        if (!organization.storeExists(storeId)) {
            throw CatalogEntryNotFoundException.store(storeId);
        }
        Item item = items.findById(itemId).orElseThrow(() -> CatalogEntryNotFoundException.item(itemId));
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().strip();
        boolean changed = exclusions.insertIfAbsent(storeId, itemId, reason, clock.instant(),
                accessPolicy.current().userId()) == 1 || exclusions.changeReason(storeId, itemId, reason) == 1;
        if (changed) {
            Map<String, Object> details = new HashMap<>();
            if (reason != null) {
                details.put("reason", reason);
            }
            audit.record("ITEM_EXCLUDED_FROM_STORE", "Item", itemId, storeId, details);
        }
        return view(exclusions.findByIdStoreIdAndIdItemId(storeId, itemId).orElseThrow(), item);
    }

    /** @throws CatalogEntryNotFoundException 404 when the item is not excluded from the store */
    @Transactional
    public void include(UUID storeId, UUID itemId) {
        accessPolicy.require(Permission.CATALOG_MANAGE, storeId);
        if (exclusions.remove(storeId, itemId) == 0) {
            throw new CatalogEntryNotFoundException("Exclusion of item " + itemId + " from store", storeId);
        }
        audit.record("ITEM_INCLUDED_IN_STORE", "Item", itemId, storeId, Map.of());
    }

    private static StoreItemExclusionView view(StoreItemExclusion exclusion, Item item) {
        return exclusion.toView(item.getCode(), item.getName());
    }
}
