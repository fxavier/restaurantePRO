package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.model.SoldOutFlag;
import com.xavier.backend.catalog.model.dto.AvailabilityRequest;
import com.xavier.backend.catalog.model.dto.ItemAvailabilityView;
import com.xavier.backend.catalog.repository.ItemRepository;
import com.xavier.backend.catalog.repository.ItemVariantRepository;
import com.xavier.backend.catalog.repository.SoldOutFlagRepository;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sold-out flag (D109): operational availability per store × item or variant, outside the release, so no
 * republication. A POS session sets it for its own store ({@code CATALOG_AVAILABILITY}); setting the state it already
 * has changes nothing. A change is audited and, after the commit, tells the store's POS clients to re-read
 * ({@code CATALOG} notice).
 */
@Service
public class AvailabilityService {

    private final SoldOutFlagRepository flags;
    private final ItemRepository items;
    private final ItemVariantRepository variants;
    private final AccessPolicy accessPolicy;
    private final CatalogAudit audit;
    private final ChangeNotifier notifier;
    private final Clock clock;

    AvailabilityService(SoldOutFlagRepository flags, ItemRepository items, ItemVariantRepository variants,
                        AccessPolicy accessPolicy, CatalogAudit audit, ChangeNotifier notifier, Clock clock) {
        this.flags = flags;
        this.items = items;
        this.variants = variants;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** @throws CatalogEntryNotFoundException (404) for an unknown item, or a variant that is not one of its variants */
    @Transactional
    public ItemAvailabilityView set(UUID itemId, AvailabilityRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.CATALOG_AVAILABILITY);
        requireItem(itemId, request.variantId());
        UUID storeId = pos.storeId();
        boolean changed = request.soldOut() ? markSoldOut(storeId, itemId, request) : markAvailable(storeId, itemId,
                request.variantId());
        if (changed) {
            Map<String, Object> details = new HashMap<>();
            if (request.variantId() != null) {
                details.put("variantId", request.variantId().toString());
            }
            if (request.soldOut() && request.reason() != null) {
                details.put("reason", request.reason());
            }
            audit.record(request.soldOut() ? "ITEM_SOLD_OUT" : "ITEM_BACK_IN_STOCK", "Item", itemId, storeId, details);
            notifier.notifyAfterCommit(storeId, ChangeTopic.CATALOG, null);
        }
        return flag(storeId, itemId, request.variantId()).map(SoldOutFlag::toView)
                .orElseGet(() -> new ItemAvailabilityView(itemId, request.variantId(), false, null, null));
    }

    /** The sold-out keys of the POS session's store ({@code CATALOG_VIEW}). */
    @Transactional(readOnly = true)
    public List<ItemAvailabilityView> soldOut() {
        UUID storeId = accessPolicy.requirePos(Permission.CATALOG_VIEW).storeId();
        return flags.findAllByStoreIdOrderBySoldOutAtAscIdAsc(storeId).stream().map(SoldOutFlag::toView).toList();
    }

    private boolean markSoldOut(UUID storeId, UUID itemId, AvailabilityRequest request) {
        Actor actor = accessPolicy.current();
        return flags.insertIfAbsent(storeId, itemId, request.variantId(), clock.instant(), actor.kind().name(),
                actor.userId(), request.reason()) == 1;
    }

    private boolean markAvailable(UUID storeId, UUID itemId, UUID variantId) {
        return flag(storeId, itemId, variantId).map(flag -> flags.remove(flag.getId()) == 1).orElse(false);
    }

    private Optional<SoldOutFlag> flag(UUID storeId, UUID itemId, UUID variantId) {
        return flags.findByStoreIdAndItemIdAndVariantId(storeId, itemId, variantId);
    }

    private void requireItem(UUID itemId, UUID variantId) {
        if (!items.existsById(itemId)) {
            throw new CatalogEntryNotFoundException("Item", itemId);
        }
        if (variantId != null && variants.findByIdAndItemId(variantId, itemId).isEmpty()) {
            throw new CatalogEntryNotFoundException("Item variant", variantId);
        }
    }
}
