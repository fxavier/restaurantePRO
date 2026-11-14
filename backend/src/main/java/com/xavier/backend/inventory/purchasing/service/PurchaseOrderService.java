package com.xavier.backend.inventory.purchasing.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.purchasing.exception.PurchasingConflictException;
import com.xavier.backend.inventory.purchasing.exception.PurchasingRuleException;
import com.xavier.backend.inventory.purchasing.model.PurchaseDocumentStatus;
import com.xavier.backend.inventory.purchasing.model.PurchaseOrder;
import com.xavier.backend.inventory.purchasing.model.PurchaseOrderLine;
import com.xavier.backend.inventory.purchasing.model.ReceiptProgress;
import com.xavier.backend.inventory.purchasing.model.dto.PurchaseLineRequest;
import com.xavier.backend.inventory.purchasing.model.dto.PurchaseOrderRequest;
import com.xavier.backend.inventory.purchasing.model.dto.PurchaseOrderUpdateRequest;
import com.xavier.backend.inventory.purchasing.model.dto.PurchaseOrderView;
import com.xavier.backend.inventory.purchasing.repository.GoodsReceiptRepository;
import com.xavier.backend.inventory.purchasing.repository.PurchaseOrderRepository;
import com.xavier.backend.inventory.purchasing.repository.Received;
import com.xavier.backend.inventory.purchasing.repository.SupplierItemRepository;
import com.xavier.backend.inventory.purchasing.repository.SupplierRepository;
import com.xavier.backend.inventory.purchasing.model.SupplierItem;
import com.xavier.backend.inventory.service.InventoryScope;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase orders (D124): {@code PURCHASE_MANAGE} in the warehouse's store to create, edit (DRAFT), submit, close
 * and cancel; {@code INVENTORY_VIEW} there to read, with the receipt progress derived from confirmed receipts. An
 * order never moves stock. Commands find the order's store alone (404), authorize (403), then lock the order row.
 * Lines default their cost to the supplier's last cost (D123). Audited.
 */
@Service
public class PurchaseOrderService {

    static final String ENTITY = "PurchaseOrder";
    static final String TABLE = "inventory.purchase_order";

    private final PurchaseOrderRepository orders;
    private final GoodsReceiptRepository receipts;
    private final SupplierRepository suppliers;
    private final SupplierItemRepository supplierItems;
    private final PurchaseUnits units;
    private final InventoryScope scope;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final EntityManager entityManager;
    private final Clock clock;

    PurchaseOrderService(PurchaseOrderRepository orders, GoodsReceiptRepository receipts,
                         SupplierRepository suppliers, SupplierItemRepository supplierItems, PurchaseUnits units,
                         InventoryScope scope, AccessPolicy accessPolicy, AuditLog auditLog,
                         EntityManager entityManager, Clock clock) {
        this.orders = orders;
        this.receipts = receipts;
        this.suppliers = suppliers;
        this.supplierItems = supplierItems;
        this.units = units;
        this.scope = scope;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** @throws PurchasingConflictException PO_NUMBER_TAKEN in the store */
    @Transactional
    public PurchaseOrderView create(PurchaseOrderRequest request) {
        UUID storeId = scope.requireWarehouse(Permission.PURCHASE_MANAGE, request.warehouseId());
        if (!suppliers.existsById(request.supplierId())) {
            throw new InventoryNotFoundException(SupplierService.ENTITY, request.supplierId());
        }
        PurchaseOrder order = UniqueConstraints.saveAndFlush(orders, PurchaseOrder.draft(storeId,
                        request.warehouseId(), request.supplierId(), request.number(),
                        lines(request.supplierId(), request.lines()), request.expectedAt(), request.notes(),
                        accessPolicy.current().userId(), clock.instant()),
                () -> PurchasingConflictException.orderNumberTaken(storeId, request.number().strip()),
                "purchase_order_number_uk");
        audit("PURCHASE_ORDER_CREATED", order);
        return view(order);
    }

    @Transactional
    public PurchaseOrderView update(UUID id, PurchaseOrderUpdateRequest request) {
        accessPolicy.require(Permission.PURCHASE_MANAGE, storeOf(id));
        PurchaseOrder order = orders.findById(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
        List<PurchaseOrderLine> lines = lines(order.getSupplierId(), request.lines()); // checked before the lock
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        OptimisticLocking.requireVersion(PurchaseOrder.class, id, order.getVersion(), request.version());
        order.edit(lines, request.expectedAt(), request.notes());
        PurchaseOrder saved = orders.saveAndFlush(order);
        audit("PURCHASE_ORDER_UPDATED", saved);
        return view(saved);
    }

    @Transactional
    public PurchaseOrderView submit(UUID id) {
        PurchaseOrder order = lockAuthorized(id);
        order.submit(clock.instant());
        return saved("PURCHASE_ORDER_SUBMITTED", order);
    }

    @Transactional
    public PurchaseOrderView close(UUID id) {
        PurchaseOrder order = lockAuthorized(id);
        order.close(clock.instant());
        return saved("PURCHASE_ORDER_CLOSED", order);
    }

    /**
     * Under the order's lock, so a receipt confirming at the same time either lands first (and the cancel is
     * refused) or finds the order cancelled.
     *
     * @throws PurchasingRuleException PO_HAS_RECEIPTS once a receipt against it is confirmed
     */
    @Transactional
    public PurchaseOrderView cancel(UUID id) {
        PurchaseOrder order = lockAuthorized(id);
        order.cancel(receipts.existsByPurchaseOrderIdAndStatus(id, PurchaseDocumentStatus.CONFIRMED),
                clock.instant());
        return saved("PURCHASE_ORDER_CANCELLED", order);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderView get(UUID id) {
        accessPolicy.require(Permission.INVENTORY_VIEW, storeOf(id));
        return view(orders.findById(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id)));
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderView> list(UUID storeId, Pageable pageable) {
        scope.requireStore(Permission.INVENTORY_VIEW, storeId);
        return PageResponse.from(orders.findByStoreIdOrderByCreatedAtDesc(storeId, pageable).map(this::view));
    }

    private List<PurchaseOrderLine> lines(UUID supplierId, List<PurchaseLineRequest> requested) {
        List<PurchaseOrderLine> lines = new ArrayList<>();
        for (PurchaseLineRequest line : requested) {
            PurchaseUnits.Unit unit = units.of(line.stockItemId(), line.packId());
            BigDecimal cost = line.unitCost() != null ? unit.costPerBase(line.unitCost())
                    : supplierItems.find(supplierId, line.stockItemId(), unit.packId())
                    .map(SupplierItem::getLastUnitCost)
                    .orElseThrow(() -> PurchasingRuleException.unitCostRequired(line.stockItemId()));
            BigDecimal quantityBase = PurchaseUnits.requireQuantity(line.stockItemId(), unit.toBase(line.quantity()));
            PurchaseUnits.requireValue(line.stockItemId(), quantityBase, cost);
            lines.add(PurchaseOrderLine.of(line.stockItemId(), unit.packId(), line.quantity(), quantityBase, cost));
        }
        return lines;
    }

    private UUID storeOf(UUID id) {
        return scope.storeOf(TABLE, ENTITY, id);
    }

    /** {@code PURCHASE_MANAGE} in the order's store (found by its id alone first), then the row FOR UPDATE. */
    private PurchaseOrder lockAuthorized(UUID id) {
        accessPolicy.require(Permission.PURCHASE_MANAGE, storeOf(id));
        PurchaseOrder order = orders.findById(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        return order;
    }

    private PurchaseOrderView saved(String action, PurchaseOrder order) {
        PurchaseOrder saved = orders.saveAndFlush(order);
        audit(action, saved);
        return view(saved);
    }

    private void audit(String action, PurchaseOrder order) {
        List<Map<String, Object>> lines = order.getLines().stream().<Map<String, Object>>map(line -> Map.of(
                "stockItemId", line.getStockItemId().toString(), "quantityBase", line.getQuantityBase()
                        .toPlainString(), "unitCost", line.getUnitCost().toPlainString())).toList();
        auditLog.recordAsCurrentActor(action, ENTITY, order.getId().toString(), order.getStoreId(), null, null,
                Map.of("number", order.getNumber(), "supplierId", order.getSupplierId().toString(),
                        "warehouseId", order.getWarehouseId().toString(), "status", order.getStatus().name(),
                        "lines", lines));
    }

    private PurchaseOrderView view(PurchaseOrder order) {
        Map<UUID, BigDecimal> received = receipts.receivedOf(order.getId()).stream()
                .collect(Collectors.toMap(Received::purchaseOrderLineId, Received::quantityBase));
        List<PurchaseOrderView.Line> lines = new ArrayList<>();
        List<ReceiptProgress> progress = new ArrayList<>();
        for (PurchaseOrderLine line : order.getLines()) {
            BigDecimal arrived = received.getOrDefault(line.getId(), BigDecimal.ZERO);
            ReceiptProgress lineProgress = ReceiptProgress.of(line.getQuantityBase(), arrived);
            progress.add(lineProgress);
            lines.add(new PurchaseOrderView.Line(line.getId(), line.getStockItemId(), line.getPackId(),
                    line.getQuantity(), line.getQuantityBase(), line.getUnitCost(), arrived,
                    line.getQuantityBase().subtract(arrived).max(BigDecimal.ZERO), lineProgress.name()));
        }
        return new PurchaseOrderView(order.getId(), order.getStoreId(), order.getWarehouseId(),
                order.getSupplierId(), order.getNumber(), order.getStatus().name(),
                ReceiptProgress.ofOrder(progress).name(), order.getExpectedAt(), order.getNotes(),
                order.getCreatedAt(), order.getSubmittedAt(), order.getClosedAt(), order.getCancelledAt(), lines,
                order.getVersion());
    }
}
