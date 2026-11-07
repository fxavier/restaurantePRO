package com.xavier.backend.inventory.purchasing.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.exception.InventoryConflictException;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.purchasing.exception.PurchasingConflictException;
import com.xavier.backend.inventory.purchasing.model.Supplier;
import com.xavier.backend.inventory.purchasing.model.SupplierItem;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierItemRequest;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierItemView;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierRequest;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierUpdateRequest;
import com.xavier.backend.inventory.purchasing.model.dto.SupplierView;
import com.xavier.backend.inventory.purchasing.repository.SupplierItemRepository;
import com.xavier.backend.inventory.purchasing.repository.SupplierRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suppliers and what they sell (D123). Company-wide: {@code PURCHASE_MANAGE} in any store (PRD R4 conventions).
 * Audited.
 */
@Service
public class SupplierService {

    static final String ENTITY = "Supplier";

    private final SupplierRepository suppliers;
    private final SupplierItemRepository supplierItems;
    private final PurchaseUnits units;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    SupplierService(SupplierRepository suppliers, SupplierItemRepository supplierItems, PurchaseUnits units,
                    AccessPolicy accessPolicy, AuditLog auditLog, Clock clock) {
        this.suppliers = suppliers;
        this.supplierItems = supplierItems;
        this.units = units;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional
    public SupplierView create(SupplierRequest request) {
        accessPolicy.requireInAnyStore(Permission.PURCHASE_MANAGE);
        Supplier supplier = save(Supplier.create(new Supplier.Contact(request.code(), request.name(),
                request.taxId(), request.email(), request.phone(), request.notes()), clock.instant()));
        audit("SUPPLIER_CREATED", supplier);
        return view(supplier);
    }

    @Transactional
    public SupplierView update(UUID id, SupplierUpdateRequest request) {
        accessPolicy.requireInAnyStore(Permission.PURCHASE_MANAGE);
        Supplier supplier = find(id);
        OptimisticLocking.requireVersion(Supplier.class, id, supplier.getVersion(), request.version());
        supplier.update(new Supplier.Contact(request.code(), request.name(), request.taxId(), request.email(),
                request.phone(), request.notes()), request.active(), clock.instant());
        Supplier saved = save(supplier);
        audit("SUPPLIER_UPDATED", saved);
        return view(saved);
    }

    @Transactional(readOnly = true)
    public SupplierView get(UUID id) {
        accessPolicy.requireInAnyStore(Permission.PURCHASE_MANAGE);
        return view(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<SupplierView> list(Pageable pageable) {
        accessPolicy.requireInAnyStore(Permission.PURCHASE_MANAGE);
        return PageResponse.from(suppliers.findAllByOrderByCodeAsc(pageable).map(SupplierService::view));
    }

    /** @throws PurchasingConflictException SUPPLIER_ITEM_EXISTS for the same stock item and pack */
    @Transactional
    public SupplierItemView addItem(UUID supplierId, SupplierItemRequest request) {
        accessPolicy.requireInAnyStore(Permission.PURCHASE_MANAGE);
        find(supplierId);
        PurchaseUnits.Unit unit = units.of(request.stockItemId(), request.packId());
        SupplierItem item = UniqueConstraints.saveAndFlush(supplierItems,
                SupplierItem.of(supplierId, request.stockItemId(), unit.packId(), request.supplierCode(),
                        request.unitCost() == null ? null : unit.costPerBase(request.unitCost()), clock.instant()),
                () -> PurchasingConflictException.supplierItemExists(supplierId, request.stockItemId()),
                "supplier_item_uk");
        Map<String, Object> details = new HashMap<>();
        details.put("stockItemId", item.getStockItemId().toString());
        if (item.getPackId() != null) {
            details.put("packId", item.getPackId().toString());
        }
        if (item.getLastUnitCost() != null) {
            details.put("lastUnitCost", item.getLastUnitCost().toPlainString());
        }
        auditLog.recordAsCurrentActor("SUPPLIER_ITEM_ADDED", ENTITY, supplierId.toString(), null, null, null,
                details);
        return view(item);
    }

    @Transactional(readOnly = true)
    public List<SupplierItemView> items(UUID supplierId) {
        accessPolicy.requireInAnyStore(Permission.PURCHASE_MANAGE);
        find(supplierId);
        return supplierItems.findBySupplierIdOrderByStockItemId(supplierId).stream().map(SupplierService::view)
                .toList();
    }

    private Supplier find(UUID id) {
        return suppliers.findById(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
    }

    private Supplier save(Supplier supplier) {
        return UniqueConstraints.saveAndFlush(suppliers, supplier,
                () -> InventoryConflictException.codeTaken("SUPPLIER", supplier.getCode()), "supplier_code_uk");
    }

    private void audit(String action, Supplier supplier) {
        auditLog.recordAsCurrentActor(action, ENTITY, supplier.getId().toString(), null, null, null,
                Map.of("code", supplier.getCode(), "name", supplier.getName(), "active", supplier.isActive()));
    }

    static SupplierView view(Supplier supplier) {
        return new SupplierView(supplier.getId(), supplier.getCode(), supplier.getName(), supplier.getTaxId(),
                supplier.getEmail(), supplier.getPhone(), supplier.getNotes(), supplier.isActive(),
                supplier.getVersion());
    }

    static SupplierItemView view(SupplierItem item) {
        return new SupplierItemView(item.getId(), item.getSupplierId(), item.getStockItemId(), item.getPackId(),
                item.getSupplierCode(), item.getLastUnitCost(), item.getLastCostAt());
    }
}
