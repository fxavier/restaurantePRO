package com.xavier.backend.inventory.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.exception.InventoryConflictException;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.model.StockItem;
import com.xavier.backend.inventory.model.StockItemPack;
import com.xavier.backend.inventory.model.dto.PackRequest;
import com.xavier.backend.inventory.model.dto.PackView;
import com.xavier.backend.inventory.model.dto.StockItemRequest;
import com.xavier.backend.inventory.model.dto.StockItemUpdateRequest;
import com.xavier.backend.inventory.model.dto.StockItemView;
import com.xavier.backend.inventory.repository.StockItemPackRepository;
import com.xavier.backend.inventory.repository.StockItemRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock items and their packs (D114). Company-wide data: {@code INVENTORY_MANAGE} installation-wide to write (as
 * S20, S39, S40 and suppliers), {@code INVENTORY_VIEW} installation-wide or in any store to read. Every write is
 * audited.
 */
@Service
public class StockItemService {

    static final String ENTITY = "StockItem";

    private final StockItemRepository items;
    private final StockItemPackRepository packs;
    private final Units units;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    StockItemService(StockItemRepository items, StockItemPackRepository packs, Units units, AccessPolicy accessPolicy,
                     AuditLog auditLog, Clock clock) {
        this.items = items;
        this.packs = packs;
        this.units = units;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<StockItemView> list(Pageable pageable) {
        accessPolicy.requireInAnyStore(Permission.INVENTORY_VIEW);
        return PageResponse.from(items.findAllByOrderByCodeAsc(pageable).map(StockItem::toView));
    }

    @Transactional(readOnly = true)
    public StockItemView get(UUID id) {
        accessPolicy.requireInAnyStore(Permission.INVENTORY_VIEW);
        return require(id).toView();
    }

    @Transactional
    public StockItemView create(StockItemRequest request) {
        accessPolicy.require(Permission.INVENTORY_MANAGE);
        StockItem item = UniqueConstraints.saveAndFlush(items, StockItem.create(request.code(), request.name(),
                        units.unit(request.baseUnit()), request.kind(), clock.instant()),
                () -> InventoryConflictException.codeTaken("STOCK_ITEM", request.code().strip()),
                "stock_item_code_uk");
        auditLog.recordAsCurrentActor("STOCK_ITEM_CREATED", ENTITY, item.getId().toString(), null, null, null,
                Map.of("code", item.getCode(), "baseUnit", item.getBaseUnit(), "kind", item.getKind().name()));
        return item.toView();
    }

    @Transactional
    public StockItemView update(UUID id, StockItemUpdateRequest request) {
        accessPolicy.require(Permission.INVENTORY_MANAGE);
        StockItem item = require(id);
        OptimisticLocking.requireVersion(StockItem.class, id, item.getVersion(), request.version());
        item.update(request.code(), request.name(), request.active(), clock.instant());
        StockItem saved = UniqueConstraints.saveAndFlush(items, item,
                () -> InventoryConflictException.codeTaken("STOCK_ITEM", request.code().strip()),
                "stock_item_code_uk");
        auditLog.recordAsCurrentActor("STOCK_ITEM_UPDATED", ENTITY, id.toString(), null, null, null,
                Map.of("code", saved.getCode(), "active", saved.isActive()));
        return saved.toView();
    }

    @Transactional(readOnly = true)
    public List<PackView> packs(UUID id) {
        accessPolicy.requireInAnyStore(Permission.INVENTORY_VIEW);
        require(id);
        return packs.findByStockItemIdOrderByCode(id).stream().map(StockItemPack::toView).toList();
    }

    @Transactional
    public PackView addPack(UUID id, PackRequest request) {
        accessPolicy.require(Permission.INVENTORY_MANAGE);
        StockItem item = require(id);
        StockItemPack pack = UniqueConstraints.saveAndFlush(packs, StockItemPack.create(id, request.code(),
                        request.name(), units.toBase(request.quantity(), request.unit(), item.getBaseUnit())),
                () -> InventoryConflictException.codeTaken("PACK", request.code().strip()),
                "stock_item_pack_code_uk");
        auditLog.recordAsCurrentActor("STOCK_ITEM_PACK_CREATED", ENTITY, id.toString(), null, null, null,
                Map.of("packId", pack.getId().toString(), "code", pack.getCode(),
                        "quantityBase", pack.getQuantityBase().toPlainString()));
        return pack.toView();
    }

    StockItem require(UUID id) {
        return items.findById(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
    }
}
