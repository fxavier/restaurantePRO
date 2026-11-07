package com.xavier.backend.catalog.service;

import com.xavier.backend.catalog.exception.CatalogCodeTakenException;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.exception.CatalogRuleException;
import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.model.AutomaticItem;
import com.xavier.backend.catalog.model.AutomaticTrigger;
import com.xavier.backend.catalog.model.Item;
import com.xavier.backend.catalog.model.ItemLine;
import com.xavier.backend.catalog.model.dto.AutomaticItemCreateRequest;
import com.xavier.backend.catalog.model.dto.AutomaticItemSummary;
import com.xavier.backend.catalog.model.dto.AutomaticItemUpdateRequest;
import com.xavier.backend.catalog.model.dto.ItemKind;
import com.xavier.backend.catalog.repository.AutomaticItemRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Automatic items (D109), per store or for every store ({@code CATALOG_MANAGE} there, or installation-wide). The item
 * must exist when written; release validation checks it is in the release.
 */
@Service
public class AutomaticItemService {

    private final AutomaticItemRepository automaticItems;
    private final ItemLines itemLines;
    private final StoreScopedAccess access;
    private final CatalogAudit audit;
    private final Clock clock;

    AutomaticItemService(AutomaticItemRepository automaticItems, ItemLines itemLines, StoreScopedAccess access,
                         CatalogAudit audit, Clock clock) {
        this.automaticItems = automaticItems;
        this.itemLines = itemLines;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    /** {@code storeId} given: the items that store sees (its own and every store's); else the every-store ones. */
    @Transactional(readOnly = true)
    public List<AutomaticItemSummary> list(UUID storeId) {
        access.require(storeId);
        return (storeId == null ? automaticItems.findAllByStoreIdIsNullOrderById()
                : automaticItems.findAllForStore(storeId)).stream().map(AutomaticItem::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public AutomaticItemSummary get(UUID id) {
        access.requireExisting(automaticItems.findScopeById(id), "Automatic item", id);
        return automaticItem(id).toSummary();
    }

    @Transactional
    public AutomaticItemSummary create(AutomaticItemCreateRequest request) {
        access.requireForNew(request.storeId());
        AutomaticItem automatic = save(AutomaticItem.create(request.storeId(),
                details(request.trigger(), request.mode(), request.itemId(), request.variantId(), request.quantity(),
                        request.active() == null || request.active()), clock.instant()));
        audit("AUTOMATIC_ITEM_CREATED", automatic);
        return automatic.toSummary();
    }

    @Transactional
    public AutomaticItemSummary update(UUID id, AutomaticItemUpdateRequest request) {
        access.requireExisting(automaticItems.findScopeById(id), "Automatic item", id);
        AutomaticItem automatic = automaticItem(id);
        OptimisticLocking.requireVersion(AutomaticItem.class, id, automatic.getVersion(), request.version());
        automatic.update(details(request.trigger(), request.mode(), request.itemId(), request.variantId(),
                request.quantity(), request.active()), clock.instant());
        save(automatic);
        audit("AUTOMATIC_ITEM_UPDATED", automatic);
        return automatic.toSummary();
    }

    @Transactional
    public void delete(UUID id, long version) {
        access.requireExisting(automaticItems.findScopeById(id), "Automatic item", id);
        AutomaticItem automatic = automaticItem(id);
        OptimisticLocking.requireVersion(AutomaticItem.class, id, automatic.getVersion(), version);
        automaticItems.delete(automatic);
        automaticItems.flush();
        audit("AUTOMATIC_ITEM_DELETED", automatic);
    }

    private AutomaticItem.Details details(String trigger, String mode, UUID itemId, UUID variantId,
                                          BigDecimal quantity, boolean active) {
        Item item = itemLines.require(itemId, variantId, InvalidCatalogEntryException::new);
        if (item.getKind() == ItemKind.MENU) {
            throw CatalogRuleException.menuNotAllowed(item.getCode(), "an automatic item");
        }
        return new AutomaticItem.Details(AutomaticTrigger.valueOf(trigger), mode,
                new ItemLine(itemId, variantId, quantity), active);
    }

    private AutomaticItem save(AutomaticItem automatic) {
        return UniqueConstraints.saveAndFlush(automaticItems, automatic,
                () -> new CatalogCodeTakenException("An active automatic item with this trigger, mode and item"),
                "automatic_item_active_ux");
    }

    private AutomaticItem automaticItem(UUID id) {
        return automaticItems.findById(id).orElseThrow(() -> new CatalogEntryNotFoundException("Automatic item", id));
    }

    private void audit(String action, AutomaticItem automatic) {
        audit.record(action, "AutomaticItem", automatic.getId(), automatic.getStoreId(),
                Map.of("trigger", automatic.getTrigger().name(), "itemId", automatic.getLine().getItemId().toString(),
                        "active", automatic.isActive()));
    }
}
