package com.xavier.backend.inventory.purchasing.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.purchasing.exception.PurchasingConflictException;
import com.xavier.backend.inventory.purchasing.exception.PurchasingRuleException;
import com.xavier.backend.inventory.purchasing.model.GoodsReceipt;
import com.xavier.backend.inventory.purchasing.model.GoodsReceiptLine;
import com.xavier.backend.inventory.purchasing.model.PurchaseOrder;
import com.xavier.backend.inventory.purchasing.model.PurchaseOrderLine;
import com.xavier.backend.inventory.purchasing.model.ReceiptCostCorrection;
import com.xavier.backend.inventory.purchasing.model.SupplierItem;
import com.xavier.backend.inventory.purchasing.model.dto.ReceiptLineRequest;
import com.xavier.backend.inventory.purchasing.model.dto.ReceiptRequest;
import com.xavier.backend.inventory.purchasing.model.dto.ReceiptUpdateRequest;
import com.xavier.backend.inventory.purchasing.model.dto.ReceiptView;
import com.xavier.backend.inventory.purchasing.repository.CostCorrectionRepository;
import com.xavier.backend.inventory.purchasing.repository.GoodsReceiptRepository;
import com.xavier.backend.inventory.purchasing.repository.PurchaseOrderRepository;
import com.xavier.backend.inventory.purchasing.repository.ReceiptRef;
import com.xavier.backend.inventory.purchasing.repository.Received;
import com.xavier.backend.inventory.purchasing.repository.SupplierItemRepository;
import com.xavier.backend.inventory.purchasing.repository.SupplierRepository;
import com.xavier.backend.inventory.service.ActivationService;
import com.xavier.backend.inventory.service.BusinessDates;
import com.xavier.backend.inventory.service.InventoryScope;
import com.xavier.backend.inventory.service.StockLedger;
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
import java.util.stream.Collectors;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods receipts (D125, P51): {@code GOODS_RECEIVE} in the warehouse's store. A DRAFT receipt is edited freely and
 * moves nothing; its confirmation, once the store is live (D122), is the one CAS DRAFT → CONFIRMED that posts the
 * RECEIPT movements (source GOODS_RECEIPT, one per line) through the ledger and records each line's cost as the
 * supplier's last cost (D123). Against a purchase order the order must be SUBMITTED; over-receipt is allowed and
 * reported ({@code OVER_RECEIPT}). Lock order: purchase order, receipt, then the ledger's balance rows. Audited.
 */
@Service
public class GoodsReceiptService {

    public static final String SOURCE_TYPE = "GOODS_RECEIPT";
    static final String ENTITY = "GoodsReceipt";
    static final String OVER_RECEIPT = "OVER_RECEIPT";

    private final GoodsReceiptRepository receipts;
    private final PurchaseOrderRepository orders;
    private final SupplierRepository suppliers;
    private final SupplierItemRepository supplierItems;
    private final CostCorrectionRepository corrections;
    private final PurchaseUnits units;
    private final StockLedger ledger;
    private final InventoryScope scope;
    private final ActivationService activations;
    private final BusinessDates businessDates;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final EntityManager entityManager;
    private final Clock clock;

    GoodsReceiptService(GoodsReceiptRepository receipts, PurchaseOrderRepository orders,
                        SupplierRepository suppliers, SupplierItemRepository supplierItems,
                        CostCorrectionRepository corrections, PurchaseUnits units, StockLedger ledger,
                        InventoryScope scope, ActivationService activations, BusinessDates businessDates,
                        AccessPolicy accessPolicy, AuditLog auditLog, EntityManager entityManager, Clock clock) {
        this.receipts = receipts;
        this.orders = orders;
        this.suppliers = suppliers;
        this.supplierItems = supplierItems;
        this.corrections = corrections;
        this.units = units;
        this.ledger = ledger;
        this.scope = scope;
        this.activations = activations;
        this.businessDates = businessDates;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * @throws InventoryRuleException  WAREHOUSE_STORE_MISMATCH when the order is of another store
     * @throws PurchasingRuleException PO_NOT_SUBMITTED, PURCHASE_REFERENCE_MISMATCH, UNIT_COST_REQUIRED
     */
    @Transactional
    public ReceiptView create(ReceiptRequest request) {
        UUID storeId = scope.requireWarehouse(Permission.GOODS_RECEIVE, request.warehouseId());
        PurchaseOrder order = null;
        UUID supplierId = request.supplierId();
        if (request.purchaseOrderId() != null) {
            order = orders.findById(request.purchaseOrderId()).orElseThrow(() -> new InventoryNotFoundException(
                    PurchaseOrderService.ENTITY, request.purchaseOrderId()));
            if (!order.getStoreId().equals(storeId)) {
                throw InventoryRuleException.warehouseStoreMismatch(request.warehouseId(), order.getStoreId());
            }
            order.requireReceivable();
            if (supplierId != null && !supplierId.equals(order.getSupplierId())) {
                throw PurchasingRuleException.referenceMismatch("Purchase order " + order.getId()
                        + " is to supplier " + order.getSupplierId() + ", not " + supplierId + ".");
            }
            supplierId = order.getSupplierId();
        } else if (supplierId == null) {
            throw PurchasingRuleException.referenceMismatch("A receipt without a purchase order names its supplier.");
        } else if (!suppliers.existsById(supplierId)) {
            throw new InventoryNotFoundException(SupplierService.ENTITY, supplierId);
        }
        GoodsReceipt receipt = receipts.saveAndFlush(GoodsReceipt.draft(storeId, request.warehouseId(), supplierId,
                request.purchaseOrderId(), request.supplierDocumentRef(), request.receivedAt(),
                lines(order, supplierId, request.lines()), accessPolicy.current().userId(), clock.instant()));
        audit("GOODS_RECEIPT_CREATED", receipt);
        return view(receipt, List.of());
    }

    @Transactional
    public ReceiptView update(UUID id, ReceiptUpdateRequest request) {
        GoodsReceipt receipt = lockAuthorized(id, refOf(id));
        OptimisticLocking.requireVersion(GoodsReceipt.class, id, receipt.getVersion(), request.version());
        PurchaseOrder order = receipt.getPurchaseOrderId() == null ? null
                : orders.findById(receipt.getPurchaseOrderId()).orElseThrow();
        receipt.edit(request.supplierDocumentRef(),
                request.receivedAt() == null ? receipt.getReceivedAt() : request.receivedAt(),
                lines(order, receipt.getSupplierId(), request.lines()));
        GoodsReceipt saved = receipts.saveAndFlush(receipt);
        audit("GOODS_RECEIPT_UPDATED", saved);
        return view(saved, List.of());
    }

    /**
     * The CAS DRAFT → CONFIRMED (409 RECEIPT_STATE once confirmed or cancelled), under the order's lock first when
     * it has one (so it never races a cancel), then the receipt's.
     *
     * @throws InventoryRuleException  INVENTORY_NOT_ACTIVE before the store's go-live (D122)
     * @throws PurchasingRuleException PO_NOT_SUBMITTED when the order was closed or cancelled meanwhile
     */
    @Transactional
    public ReceiptView confirm(UUID id) {
        ReceiptRef ref = refOf(id);
        accessPolicy.require(Permission.GOODS_RECEIVE, ref.storeId());
        if (activations.activeFrom(ref.storeId()).isEmpty()) {
            throw InventoryRuleException.notActive(ref.storeId()); // D122: the opening count comes first (S49)
        }
        if (ref.purchaseOrderId() != null) {
            PurchaseOrder order = orders.findById(ref.purchaseOrderId()).orElseThrow();
            entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
            order.requireReceivable();
        }
        GoodsReceipt receipt = lock(id);
        Instant now = clock.instant();
        LocalDate day = businessDates.of(ref.storeId(), now);
        receipt.confirm(accessPolicy.current().userId(), now, day);
        GoodsReceipt saved = receipts.saveAndFlush(receipt);
        ledger.post(saved.getLines().stream().map(line -> PostMovement.of(saved.getWarehouseId(),
                        line.getStockItemId(), MovementType.RECEIPT, line.getQuantityBase(), SOURCE_TYPE, id, day, now)
                .atCost(line.getUnitCostBase()).withSourceLine(line.getId())).toList());
        saved.getLines().forEach(line -> supplierItems.recordLastCost(UuidVersion7Strategy.INSTANCE
                .generateUuid(null), saved.getSupplierId(), line.getStockItemId(), line.getPackId(),
                line.getUnitCostBase(), now));
        audit("GOODS_RECEIPT_CONFIRMED", saved);
        return view(saved, warnings(saved));
    }

    @Transactional
    public ReceiptView cancel(UUID id) {
        GoodsReceipt receipt = lockAuthorized(id, refOf(id));
        receipt.cancel(clock.instant());
        GoodsReceipt saved = receipts.saveAndFlush(receipt);
        audit("GOODS_RECEIPT_CANCELLED", saved);
        return view(saved, List.of());
    }

    /** {@code GOODS_RECEIVE} or {@code INVENTORY_VIEW} in the receipt's store. */
    @Transactional(readOnly = true)
    public ReceiptView get(UUID id) {
        accessPolicy.requireAny(refOf(id).storeId(), Permission.GOODS_RECEIVE, Permission.INVENTORY_VIEW);
        return view(receipts.findById(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id)), List.of());
    }

    @Transactional(readOnly = true)
    public PageResponse<ReceiptView> list(UUID storeId, Pageable pageable) {
        accessPolicy.requireAny(storeId, Permission.GOODS_RECEIVE, Permission.INVENTORY_VIEW);
        return PageResponse.from(receipts.findByStoreIdOrderByCreatedAtDesc(storeId, pageable)
                .map(receipt -> view(receipt, List.of())));
    }

    private List<GoodsReceiptLine> lines(PurchaseOrder order, UUID supplierId, List<ReceiptLineRequest> requested) {
        List<GoodsReceiptLine> lines = new ArrayList<>();
        for (ReceiptLineRequest line : requested) {
            PurchaseOrderLine orderLine = orderLine(order, line);
            PurchaseUnits.Unit unit = units.of(line.stockItemId(), line.packId());
            BigDecimal cost;
            if (line.unitCost() != null) {
                cost = unit.costPerBase(line.unitCost());
            } else if (orderLine != null) {
                cost = orderLine.getUnitCost();
            } else {
                cost = supplierItems.find(supplierId, line.stockItemId(), unit.packId())
                        .map(SupplierItem::getLastUnitCost)
                        .orElseThrow(() -> PurchasingRuleException.unitCostRequired(line.stockItemId()));
            }
            lines.add(GoodsReceiptLine.of(orderLine == null ? null : orderLine.getId(), line.stockItemId(),
                    unit.packId(), line.packQuantity(), unit.toBase(line.packQuantity()), cost));
        }
        return lines;
    }

    /** The order line a receipt line names, else the order's line of the same stock item (one per item). */
    private static PurchaseOrderLine orderLine(PurchaseOrder order, ReceiptLineRequest line) {
        if (line.purchaseOrderLineId() == null) {
            return order == null ? null : order.getLines().stream()
                    .filter(candidate -> candidate.getStockItemId().equals(line.stockItemId())).findFirst()
                    .orElse(null);
        }
        PurchaseOrderLine orderLine = order == null ? null : order.line(line.purchaseOrderLineId()).orElse(null);
        if (orderLine == null || !orderLine.getStockItemId().equals(line.stockItemId())) {
            throw PurchasingRuleException.referenceMismatch("Order line " + line.purchaseOrderLineId()
                    + " is not a line of stock item " + line.stockItemId() + " on this receipt's purchase order.");
        }
        return orderLine;
    }

    /** OVER_RECEIPT when, with this receipt, more of some order line arrived than was ordered. */
    private List<String> warnings(GoodsReceipt receipt) {
        if (receipt.getPurchaseOrderId() == null) {
            return List.of();
        }
        PurchaseOrder order = orders.findById(receipt.getPurchaseOrderId()).orElseThrow();
        Map<UUID, BigDecimal> received = receipts.receivedOf(order.getId()).stream()
                .collect(Collectors.toMap(Received::purchaseOrderLineId, Received::quantityBase));
        boolean over = order.getLines().stream().anyMatch(line ->
                received.getOrDefault(line.getId(), BigDecimal.ZERO).compareTo(line.getQuantityBase()) > 0);
        return over ? List.of(OVER_RECEIPT) : List.of();
    }

    private ReceiptRef refOf(UUID id) {
        return receipts.refOf(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
    }

    private GoodsReceipt lockAuthorized(UUID id, ReceiptRef ref) {
        accessPolicy.require(Permission.GOODS_RECEIVE, ref.storeId());
        return lock(id);
    }

    /** The receipt row FOR UPDATE, refreshed so the decision is taken on the locked state. */
    GoodsReceipt lock(UUID id) {
        GoodsReceipt receipt = receipts.findById(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
        entityManager.refresh(receipt, LockModeType.PESSIMISTIC_WRITE);
        return receipt;
    }

    /** A line's cost per base unit now: the last correction's new cost, or the cost it was received at. */
    BigDecimal currentCost(GoodsReceiptLine line) {
        return corrections.findFirstByReceiptLineIdOrderBySeqDesc(line.getId())
                .map(ReceiptCostCorrection::getNewUnitCost).orElse(line.getUnitCostBase());
    }

    private void audit(String action, GoodsReceipt receipt) {
        List<Map<String, Object>> lines = receipt.getLines().stream().<Map<String, Object>>map(line -> {
            Map<String, Object> row = new HashMap<>();
            row.put("stockItemId", line.getStockItemId().toString());
            row.put("quantityBase", line.getQuantityBase().toPlainString());
            row.put("unitCostBase", line.getUnitCostBase().toPlainString());
            return row;
        }).toList();
        Map<String, Object> details = new HashMap<>();
        details.put("warehouseId", receipt.getWarehouseId().toString());
        details.put("supplierId", receipt.getSupplierId().toString());
        details.put("status", receipt.getStatus().name());
        details.put("lines", lines);
        if (receipt.getPurchaseOrderId() != null) {
            details.put("purchaseOrderId", receipt.getPurchaseOrderId().toString());
        }
        if (receipt.getSupplierDocumentRef() != null) {
            details.put("supplierDocumentRef", receipt.getSupplierDocumentRef());
        }
        auditLog.recordAsCurrentActor(action, ENTITY, receipt.getId().toString(), receipt.getStoreId(), null, null,
                details);
    }

    private ReceiptView view(GoodsReceipt receipt, List<String> warnings) {
        return new ReceiptView(receipt.getId(), receipt.getStoreId(), receipt.getWarehouseId(),
                receipt.getSupplierId(), receipt.getPurchaseOrderId(), receipt.getSupplierDocumentRef(),
                receipt.getStatus().name(), receipt.getReceivedAt(), receipt.getCreatedAt(), receipt.getConfirmedAt(),
                receipt.getBusinessDate(), receipt.getCancelledAt(),
                receipt.getLines().stream().map(line -> new ReceiptView.Line(line.getId(),
                        line.getPurchaseOrderLineId(), line.getStockItemId(), line.getPackId(),
                        line.getPackQuantity(), line.getQuantityBase(), line.getUnitCostBase(), currentCost(line)))
                        .toList(), warnings, receipt.getVersion());
    }
}
