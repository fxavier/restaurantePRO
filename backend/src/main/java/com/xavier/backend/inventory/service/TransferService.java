package com.xavier.backend.inventory.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.AccessScope;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.StockItem;
import com.xavier.backend.inventory.model.StockTransfer;
import com.xavier.backend.inventory.model.TransferLine;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.model.dto.ReceiveRequest;
import com.xavier.backend.inventory.model.dto.TransferLineRequest;
import com.xavier.backend.inventory.model.dto.TransferRequest;
import com.xavier.backend.inventory.model.dto.TransferUpdateRequest;
import com.xavier.backend.inventory.model.dto.TransferView;
import com.xavier.backend.inventory.repository.StockItemRepository;
import com.xavier.backend.inventory.repository.StockTransferRepository;
import com.xavier.backend.inventory.repository.TransferStores;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock transfers (D120): {@code INVENTORY_TRANSFER} at the source store to create, edit, ship and cancel, and at
 * the destination store to receive. Shipping posts a TRANSFER_OUT at the source average (a manual OUT for the
 * negative policy); within one store it also posts the TRANSFER_IN at the same cost and the transfer is RECEIVED at
 * once. Between stores the destination receives later: a TRANSFER_IN of the shipped quantity at the shipping cost,
 * and a TRANSFER_SHORTAGE OUT of what did not arrive, so the destination rises by what arrived and the loss is a
 * movement of its own. Commands lock the transfer row, then the balance rows (in the ledger's order).
 */
@Service
public class TransferService {

    public static final String SOURCE_TYPE = "TRANSFER";
    static final String ENTITY = "StockTransfer";

    private final StockTransferRepository transfers;
    private final StockItemRepository items;
    private final StockLedger ledger;
    private final Units units;
    private final InventoryScope scope;
    private final BusinessDates businessDates;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final EntityManager entityManager;
    private final Clock clock;

    TransferService(StockTransferRepository transfers, StockItemRepository items, StockLedger ledger, Units units,
                    InventoryScope scope, BusinessDates businessDates, AccessPolicy accessPolicy, AuditLog auditLog,
                    EntityManager entityManager, Clock clock) {
        this.transfers = transfers;
        this.items = items;
        this.ledger = ledger;
        this.units = units;
        this.scope = scope;
        this.businessDates = businessDates;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public TransferView create(TransferRequest request) {
        UUID fromStore = scope.requireWarehouse(Permission.INVENTORY_TRANSFER, request.fromWarehouseId());
        UUID toStore = scope.storeOfWarehouse(request.toWarehouseId());
        StockTransfer transfer = transfers.saveAndFlush(StockTransfer.draft(request.fromWarehouseId(), fromStore,
                request.toWarehouseId(), toStore, lines(request.lines()), request.notes(), actorId(),
                clock.instant()));
        audit("STOCK_TRANSFER_CREATED", transfer, fromStore);
        return view(transfer);
    }

    @Transactional
    public TransferView update(UUID id, TransferUpdateRequest request) {
        TransferStores stores = storesOf(id);
        accessPolicy.require(Permission.INVENTORY_TRANSFER, stores.fromStoreId());
        StockTransfer transfer = lock(id);
        OptimisticLocking.requireVersion(StockTransfer.class, id, transfer.getVersion(), request.version());
        transfer.edit(lines(request.lines()), request.notes());
        StockTransfer saved = transfers.saveAndFlush(transfer);
        audit("STOCK_TRANSFER_UPDATED", saved, stores.fromStoreId());
        return view(saved);
    }

    @Transactional
    public TransferView ship(UUID id) {
        TransferStores stores = storesOf(id);
        accessPolicy.require(Permission.INVENTORY_TRANSFER, stores.fromStoreId());
        StockTransfer transfer = lock(id);
        Instant now = clock.instant();
        LocalDate shipDate = businessDates.of(transfer.getFromStoreId(), now);
        transfer.ship(actorId(), now);
        List<PostMovement> outs = transfer.getLines().stream().map(line -> PostMovement.of(
                transfer.getFromWarehouseId(), line.getStockItemId(), MovementType.TRANSFER_OUT,
                line.getQuantityShipped().negate(), SOURCE_TYPE, id, shipDate, now).manual("transfer")).toList();
        Map<UUID, BigDecimal> unitCosts = new HashMap<>();
        ledger.post(outs).stream().filter(m -> m.type().equals(MovementType.TRANSFER_OUT.name()))
                .forEach(m -> unitCosts.put(m.stockItemId(), m.unitCost()));
        transfer.recordShippingCosts(unitCosts);
        audit("STOCK_TRANSFER_SHIPPED", transfer, stores.fromStoreId());
        if (transfer.withinOneStore()) {
            Map<UUID, BigDecimal> everything = new HashMap<>();
            transfer.getLines().forEach(line -> everything.put(line.getStockItemId(), line.getQuantityShipped()));
            receiveInto(transfer, everything, now, shipDate);
        }
        return view(transfers.saveAndFlush(transfer));
    }

    @Transactional
    public TransferView receive(UUID id, ReceiveRequest request) {
        TransferStores stores = storesOf(id);
        accessPolicy.require(Permission.INVENTORY_TRANSFER, stores.toStoreId());
        StockTransfer transfer = lock(id);
        Map<UUID, BigDecimal> received = new HashMap<>();
        request.lines().forEach(line -> {
            if (received.put(line.stockItemId(), line.quantityReceived()) != null) {
                throw InventoryRuleException.duplicateStockItem(line.stockItemId());
            }
        });
        Instant now = clock.instant();
        receiveInto(transfer, received, now, businessDates.of(transfer.getToStoreId(), now));
        return view(transfers.saveAndFlush(transfer));
    }

    @Transactional
    public TransferView cancel(UUID id) {
        TransferStores stores = storesOf(id);
        accessPolicy.require(Permission.INVENTORY_TRANSFER, stores.fromStoreId());
        StockTransfer transfer = lock(id);
        transfer.cancel(clock.instant());
        StockTransfer saved = transfers.saveAndFlush(transfer);
        audit("STOCK_TRANSFER_CANCELLED", saved, stores.fromStoreId());
        return view(saved);
    }

    /** {@code INVENTORY_VIEW} at either end of the transfer. */
    @Transactional(readOnly = true)
    public TransferView get(UUID id) {
        TransferStores stores = storesOf(id);
        AccessScope viewers = accessPolicy.scopeOf(Permission.INVENTORY_VIEW);
        if (!viewers.includes(stores.toStoreId())) {
            accessPolicy.require(Permission.INVENTORY_VIEW, stores.fromStoreId());
        }
        return view(transfers.findById(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id)));
    }

    /** The transfers leaving or reaching {@code storeId}, newest first (in transit = SHIPPED). */
    @Transactional(readOnly = true)
    public PageResponse<TransferView> list(UUID storeId, Pageable pageable) {
        accessPolicy.require(Permission.INVENTORY_VIEW, storeId);
        return PageResponse.from(transfers.findOfStore(storeId, pageable).map(TransferService::view));
    }

    private void receiveInto(StockTransfer transfer, Map<UUID, BigDecimal> received, Instant now, LocalDate day) {
        transfer.receive(received, actorId(), now);
        List<PostMovement> movements = new ArrayList<>();
        for (TransferLine line : transfer.getLines()) {
            movements.add(PostMovement.of(transfer.getToWarehouseId(), line.getStockItemId(),
                    MovementType.TRANSFER_IN, line.getQuantityShipped(), SOURCE_TYPE, transfer.getId(), day, now)
                    .atCost(line.getUnitCost()));
            if (line.shortfall().signum() > 0) {
                movements.add(PostMovement.of(transfer.getToWarehouseId(), line.getStockItemId(),
                        MovementType.TRANSFER_SHORTAGE, line.shortfall().negate(), SOURCE_TYPE, transfer.getId(),
                        day, now).atCost(line.getUnitCost()).withReason("not received"));
            }
        }
        ledger.post(movements);
        audit("STOCK_TRANSFER_RECEIVED", transfer, transfer.getToStoreId());
    }

    private List<TransferLine> lines(List<TransferLineRequest> requested) {
        List<TransferLine> lines = new ArrayList<>();
        for (TransferLineRequest line : requested) {
            StockItem item = items.findById(line.stockItemId())
                    .orElseThrow(() -> new InventoryNotFoundException(StockItemService.ENTITY, line.stockItemId()));
            lines.add(TransferLine.of(item.getId(), units.toBase(line.quantity(), line.unit(), item.getBaseUnit())));
        }
        return lines;
    }

    private TransferStores storesOf(UUID id) {
        return transfers.storesOf(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
    }

    /** The transfer row FOR UPDATE, refreshed so the decision is taken on the locked state. */
    private StockTransfer lock(UUID id) {
        StockTransfer transfer = transfers.findById(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
        entityManager.refresh(transfer, LockModeType.PESSIMISTIC_WRITE);
        return transfer;
    }

    private UUID actorId() {
        return accessPolicy.current().userId();
    }

    private void audit(String action, StockTransfer transfer, UUID storeId) {
        List<Map<String, Object>> lines = transfer.getLines().stream().<Map<String, Object>>map(line -> {
            Map<String, Object> row = new HashMap<>();
            row.put("stockItemId", line.getStockItemId().toString());
            row.put("quantityShipped", line.getQuantityShipped().toPlainString());
            if (line.getQuantityReceived() != null) {
                row.put("quantityReceived", line.getQuantityReceived().toPlainString());
            }
            return row;
        }).toList();
        auditLog.recordAsCurrentActor(action, ENTITY, transfer.getId().toString(), storeId, null, null,
                Map.of("fromWarehouseId", transfer.getFromWarehouseId().toString(),
                        "toWarehouseId", transfer.getToWarehouseId().toString(),
                        "status", transfer.getStatus().name(), "lines", lines));
    }

    static TransferView view(StockTransfer transfer) {
        return new TransferView(transfer.getId(), transfer.getFromWarehouseId(), transfer.getToWarehouseId(),
                transfer.getFromStoreId(), transfer.getToStoreId(), transfer.getStatus().name(), transfer.getNotes(),
                transfer.getCreatedAt(), transfer.getShippedAt(), transfer.getReceivedAt(),
                transfer.getLines().stream().map(line -> new TransferView.Line(line.getStockItemId(),
                        line.getQuantityShipped(), line.getQuantityReceived(), line.getUnitCost(),
                        line.getQuantityReceived() == null ? null : line.shortfall())).toList(),
                transfer.getVersion());
    }
}
