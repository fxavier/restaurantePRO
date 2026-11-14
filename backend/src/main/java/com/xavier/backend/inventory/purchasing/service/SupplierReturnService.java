package com.xavier.backend.inventory.purchasing.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.MovingAverage;
import com.xavier.backend.inventory.model.dto.MovementView;
import com.xavier.backend.inventory.model.StockItem;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.purchasing.exception.PurchasingRuleException;
import com.xavier.backend.inventory.purchasing.model.CostVariance;
import com.xavier.backend.inventory.purchasing.model.GoodsReceipt;
import com.xavier.backend.inventory.purchasing.model.GoodsReceiptLine;
import com.xavier.backend.inventory.purchasing.model.SupplierReturn;
import com.xavier.backend.inventory.purchasing.model.SupplierReturnLine;
import com.xavier.backend.inventory.purchasing.model.dto.ReturnLineRequest;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierReturnRequest;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierReturnUpdateRequest;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierReturnView;
import com.xavier.backend.inventory.purchasing.repository.CostVarianceRepository;
import com.xavier.backend.inventory.purchasing.repository.GoodsReceiptRepository;
import com.xavier.backend.inventory.purchasing.repository.ReceiptRef;
import com.xavier.backend.inventory.purchasing.repository.SupplierRepository;
import com.xavier.backend.inventory.purchasing.repository.SupplierReturnRepository;
import com.xavier.backend.inventory.repository.StockItemRepository;
import com.xavier.backend.inventory.service.ActivationService;
import com.xavier.backend.inventory.service.BusinessDates;
import com.xavier.backend.inventory.service.InventoryScope;
import com.xavier.backend.inventory.service.StockLedger;
import com.xavier.backend.inventory.service.Units;
import com.xavier.backend.organization.OrganizationApi;
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
 * Returns to a supplier (D125): {@code GOODS_RECEIVE} in the warehouse's store. Confirmation, once the store is live,
 * posts SUPPLIER_RETURN OUTs (source SUPPLIER_RETURN, one per line) valued at the current average like every OUT
 * (D113); a line linked to a receipt line names that line's current cost for reference only, books the supplier's
 * credit against the average, {@code (average − receipt cost) × quantity}, as that receipt line's cost variance (D125
 * amended; a positive variance is always more cost of goods), and returns at most what it received less what was already returned (422 RETURN_EXCEEDS_RECEIVED). A return is a manual OUT for the
 * warehouse's negative policy (422 NEGATIVE_STOCK_REFUSED, D118). Lock order: the receipt (serializing the returns of
 * its lines), the return, then the ledger's balance rows. Audited.
 */
@Service
public class SupplierReturnService {

    public static final String SOURCE_TYPE = "SUPPLIER_RETURN";
    static final String ENTITY = "SupplierReturn";

    private final SupplierReturnRepository returns;
    private final GoodsReceiptRepository receipts;
    private final SupplierRepository suppliers;
    private final StockItemRepository items;
    private final GoodsReceiptService receiptService;
    private final Units units;
    private final StockLedger ledger;
    private final InventoryScope scope;
    private final ActivationService activations;
    private final BusinessDates businessDates;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final CostVarianceRepository variances;
    private final OrganizationApi organization;
    private final EntityManager entityManager;
    private final Clock clock;

    SupplierReturnService(SupplierReturnRepository returns, GoodsReceiptRepository receipts,
                          SupplierRepository suppliers, StockItemRepository items,
                          GoodsReceiptService receiptService, Units units, StockLedger ledger, InventoryScope scope,
                          ActivationService activations, BusinessDates businessDates, AccessPolicy accessPolicy,
                          AuditLog auditLog, CostVarianceRepository variances, OrganizationApi organization,
                          EntityManager entityManager, Clock clock) {
        this.returns = returns;
        this.receipts = receipts;
        this.suppliers = suppliers;
        this.items = items;
        this.receiptService = receiptService;
        this.units = units;
        this.ledger = ledger;
        this.scope = scope;
        this.activations = activations;
        this.businessDates = businessDates;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.variances = variances;
        this.organization = organization;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** @throws PurchasingRuleException RECEIPT_NOT_CONFIRMED, PURCHASE_REFERENCE_MISMATCH */
    @Transactional
    public SupplierReturnView create(SupplierReturnRequest request) {
        UUID storeId = scope.requireWarehouse(Permission.GOODS_RECEIVE, request.warehouseId());
        GoodsReceipt receipt = null;
        UUID supplierId = request.supplierId();
        if (request.goodsReceiptId() != null) {
            // probed across stores: another store's receipt is this rule's 422, not an absent receipt (D42, D162)
            if (!receiptService.storeOf(request.goodsReceiptId()).equals(storeId)) {
                throw PurchasingRuleException.referenceMismatch("Goods receipt " + request.goodsReceiptId()
                        + " was received in another store: return it from there.");
            }
            receipt = receipts.findById(request.goodsReceiptId()).orElseThrow(() -> new InventoryNotFoundException(
                    GoodsReceiptService.ENTITY, request.goodsReceiptId()));
            receipt.requireConfirmed();
            if (!receipt.getWarehouseId().equals(request.warehouseId())) {
                throw PurchasingRuleException.referenceMismatch("Goods receipt " + receipt.getId()
                        + " was received in warehouse " + receipt.getWarehouseId() + ": return it from there.");
            }
            if (supplierId != null && !supplierId.equals(receipt.getSupplierId())) {
                throw PurchasingRuleException.referenceMismatch("Goods receipt " + receipt.getId()
                        + " is from supplier " + receipt.getSupplierId() + ", not " + supplierId + ".");
            }
            supplierId = receipt.getSupplierId();
        } else if (supplierId == null) {
            throw PurchasingRuleException.referenceMismatch("A return without a receipt names its supplier.");
        } else if (!suppliers.existsById(supplierId)) {
            throw new InventoryNotFoundException(SupplierService.ENTITY, supplierId);
        }
        SupplierReturn supplierReturn = returns.saveAndFlush(SupplierReturn.draft(storeId, request.warehouseId(),
                supplierId, request.goodsReceiptId(), request.reason(), lines(receipt, request.lines()),
                accessPolicy.current().userId(), clock.instant()));
        audit("SUPPLIER_RETURN_CREATED", supplierReturn);
        return view(supplierReturn);
    }

    @Transactional
    public SupplierReturnView update(UUID id, SupplierReturnUpdateRequest request) {
        accessPolicy.require(Permission.GOODS_RECEIVE, storeOf(id));
        ReceiptRef ref = refOf(id);
        GoodsReceipt receipt = ref.sourceId() == null ? null
                : receipts.findById(ref.sourceId()).orElseThrow();
        List<SupplierReturnLine> lines = lines(receipt, request.lines()); // checked before the lock
        SupplierReturn supplierReturn = lock(id);
        OptimisticLocking.requireVersion(SupplierReturn.class, id, supplierReturn.getVersion(), request.version());
        supplierReturn.edit(request.reason(), lines);
        SupplierReturn saved = returns.saveAndFlush(supplierReturn);
        audit("SUPPLIER_RETURN_UPDATED", saved);
        return view(saved);
    }

    /**
     * DRAFT → CONFIRMED (409 RETURN_STATE otherwise).
     *
     * @throws InventoryRuleException  INVENTORY_NOT_ACTIVE before go-live, NEGATIVE_STOCK_REFUSED
     * @throws PurchasingRuleException RETURN_EXCEEDS_RECEIVED
     */
    @Transactional
    public SupplierReturnView confirm(UUID id) {
        accessPolicy.require(Permission.GOODS_RECEIVE, storeOf(id));
        ReceiptRef ref = refOf(id);
        if (activations.activeFrom(ref.storeId()).isEmpty()) {
            throw InventoryRuleException.notActive(ref.storeId());
        }
        GoodsReceipt receipt = ref.sourceId() == null ? null : receiptService.lock(ref.sourceId());
        SupplierReturn supplierReturn = lock(id);
        Instant now = clock.instant();
        LocalDate day = businessDates.of(ref.storeId(), now);
        supplierReturn.confirm(accessPolicy.current().userId(), now, day);
        SupplierReturn saved = returns.saveAndFlush(supplierReturn);
        List<PostMovement> movements = new ArrayList<>();
        Map<UUID, BigDecimal> receiptCosts = new HashMap<>();
        for (SupplierReturnLine line : saved.getLines()) {
            PostMovement out = PostMovement.of(saved.getWarehouseId(), line.getStockItemId(),
                    MovementType.SUPPLIER_RETURN, line.getQuantityBase().negate(), SOURCE_TYPE, id, day, now)
                    .withSourceLine(line.getId()).manual(saved.getReason() == null ? "supplier return"
                            : saved.getReason());
            if (line.getReceiptLineId() != null) {
                GoodsReceiptLine received = receipt.line(line.getReceiptLineId()).orElseThrow();
                requireReturnable(received, line, id);
                BigDecimal receiptCost = receiptService.currentCost(received);
                receiptCosts.put(line.getId(), receiptCost);
                out = out.atCost(receiptCost); // for reference: OUTs go at the average
            }
            movements.add(out);
        }
        String currency = organization.companyProfile().currency().getCurrencyCode();
        for (MovementView posted : ledger.post(movements)) {
            BigDecimal receiptCost = receiptCosts.get(posted.sourceLineId());
            if (receiptCost == null || !posted.type().equals(MovementType.SUPPLIER_RETURN.name())) {
                continue;
            }
            BigDecimal credited = MovingAverage.valueOf(posted.quantity().negate(), receiptCost);
            // (average − receipt cost) × q: positive is always more cost of goods; a credit above the average lowers it
            BigDecimal variance = posted.value().negate().subtract(credited);
            if (variance.signum() != 0) {
                SupplierReturnLine line = saved.getLines().stream()
                        .filter(candidate -> candidate.getId().equals(posted.sourceLineId())).findFirst().orElseThrow();
                variances.saveAndFlush(CostVariance.ofReturn(saved.getStoreId(), saved.getWarehouseId(),
                        line.getStockItemId(), day, variance, currency, line.getId(), line.getReceiptLineId()));
            }
        }
        audit("SUPPLIER_RETURN_CONFIRMED", saved);
        return view(saved);
    }

    @Transactional
    public SupplierReturnView cancel(UUID id) {
        accessPolicy.require(Permission.GOODS_RECEIVE, storeOf(id));
        SupplierReturn supplierReturn = lock(id);
        supplierReturn.cancel(clock.instant());
        SupplierReturn saved = returns.saveAndFlush(supplierReturn);
        audit("SUPPLIER_RETURN_CANCELLED", saved);
        return view(saved);
    }

    @Transactional(readOnly = true)
    public SupplierReturnView get(UUID id) {
        accessPolicy.requireAny(storeOf(id), Permission.GOODS_RECEIVE, Permission.INVENTORY_VIEW);
        return view(returns.findById(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id)));
    }

    @Transactional(readOnly = true)
    public PageResponse<SupplierReturnView> list(UUID storeId, Pageable pageable) {
        accessPolicy.requireAny(storeId, Permission.GOODS_RECEIVE, Permission.INVENTORY_VIEW);
        return PageResponse.from(returns.findByStoreIdOrderByCreatedAtDesc(storeId, pageable)
                .map(SupplierReturnService::view));
    }

    /** What the receipt line received less what its other confirmed returns took back. */
    private void requireReturnable(GoodsReceiptLine received, SupplierReturnLine line, UUID returnId) {
        BigDecimal available = received.getQuantityBase()
                .subtract(returns.returnedByOthers(received.getId(), returnId));
        if (line.getQuantityBase().compareTo(available) > 0) {
            throw PurchasingRuleException.returnExceedsReceived(received.getId(), available);
        }
    }

    private List<SupplierReturnLine> lines(GoodsReceipt receipt, List<ReturnLineRequest> requested) {
        List<SupplierReturnLine> lines = new ArrayList<>();
        for (ReturnLineRequest line : requested) {
            StockItem item = items.findById(line.stockItemId())
                    .orElseThrow(() -> new InventoryNotFoundException("StockItem", line.stockItemId()));
            if (line.receiptLineId() != null) {
                GoodsReceiptLine receiptLine = receipt == null ? null
                        : receipt.line(line.receiptLineId()).orElse(null);
                if (receiptLine == null || !receiptLine.getStockItemId().equals(item.getId())) {
                    throw PurchasingRuleException.referenceMismatch("Receipt line " + line.receiptLineId()
                            + " is not a line of stock item " + item.getId() + " on this return's receipt.");
                }
            }
            BigDecimal quantity = PurchaseUnits.requireQuantity(item.getId(),
                    units.toBase(line.quantity(), line.unit(), item.getBaseUnit()));
            lines.add(SupplierReturnLine.of(line.receiptLineId(), item.getId(), quantity));
        }
        return lines;
    }

    /** D42 (b): the return's store, whatever the caller's store scope (404 when there is no such return). */
    private UUID storeOf(UUID id) {
        return scope.storeOf("inventory.supplier_return", ENTITY, id);
    }

    /** The store and receipt of a return the caller was authorized for. */
    private ReceiptRef refOf(UUID id) {
        return returns.refOf(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
    }

    private SupplierReturn lock(UUID id) {
        SupplierReturn supplierReturn = returns.findById(id)
                .orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
        entityManager.refresh(supplierReturn, LockModeType.PESSIMISTIC_WRITE);
        return supplierReturn;
    }

    private void audit(String action, SupplierReturn supplierReturn) {
        List<Map<String, Object>> lines = supplierReturn.getLines().stream().<Map<String, Object>>map(line -> Map.of(
                "stockItemId", line.getStockItemId().toString(),
                "quantityBase", line.getQuantityBase().toPlainString())).toList();
        auditLog.recordAsCurrentActor(action, ENTITY, supplierReturn.getId().toString(), supplierReturn.getStoreId(),
                null, supplierReturn.getReason(), Map.of("warehouseId", supplierReturn.getWarehouseId().toString(),
                        "supplierId", supplierReturn.getSupplierId().toString(),
                        "status", supplierReturn.getStatus().name(), "lines", lines));
    }

    static SupplierReturnView view(SupplierReturn supplierReturn) {
        return new SupplierReturnView(supplierReturn.getId(), supplierReturn.getStoreId(),
                supplierReturn.getWarehouseId(), supplierReturn.getSupplierId(), supplierReturn.getGoodsReceiptId(),
                supplierReturn.getReason(), supplierReturn.getStatus().name(), supplierReturn.getCreatedAt(),
                supplierReturn.getConfirmedAt(), supplierReturn.getBusinessDate(), supplierReturn.getCancelledAt(),
                supplierReturn.getLines().stream().map(line -> new SupplierReturnView.Line(line.getId(),
                        line.getReceiptLineId(), line.getStockItemId(), line.getQuantityBase())).toList(),
                supplierReturn.getVersion());
    }
}
