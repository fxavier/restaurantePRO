package com.xavier.backend.inventory.purchasing.model;

import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.purchasing.exception.PurchasingConflictException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * Goods sent back to a supplier from one warehouse (D125; {@code DRAFT → CONFIRMED}, {@code DRAFT → CANCELLED}).
 * Confirmation posts SUPPLIER_RETURN OUTs at the average; linked to a receipt, it returns at most what each line
 * received less what was returned of it. One line per stock item.
 */
@Entity
@Table(schema = "inventory", name = "supplier_return")
public class SupplierReturn {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(nullable = false, updatable = false)
    private UUID supplierId;

    @Column(updatable = false)
    private UUID goodsReceiptId;

    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PurchaseDocumentStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(updatable = false)
    private UUID createdByAccountId;

    private Instant confirmedAt;

    private UUID confirmedByAccountId;

    private LocalDate businessDate;

    private Instant cancelledAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(schema = "inventory", name = "supplier_return_line", joinColumns = @JoinColumn(name = "return_id"))
    private List<SupplierReturnLine> lines = new ArrayList<>();

    @Version
    private Long version;

    protected SupplierReturn() {
        // JPA
    }

    public static SupplierReturn draft(UUID storeId, UUID warehouseId, UUID supplierId, UUID goodsReceiptId,
                                       String reason, List<SupplierReturnLine> lines, UUID accountId, Instant now) {
        SupplierReturn supplierReturn = new SupplierReturn();
        supplierReturn.storeId = storeId;
        supplierReturn.warehouseId = warehouseId;
        supplierReturn.supplierId = supplierId;
        supplierReturn.goodsReceiptId = goodsReceiptId;
        supplierReturn.status = PurchaseDocumentStatus.DRAFT;
        supplierReturn.createdAt = now;
        supplierReturn.createdByAccountId = accountId;
        supplierReturn.edit(reason, lines);
        return supplierReturn;
    }

    public void edit(String newReason, List<SupplierReturnLine> newLines) {
        requireStatus(PurchaseDocumentStatus.DRAFT, "be edited");
        if (newLines.isEmpty()) {
            throw InventoryRuleException.invalidQuantity("A supplier return returns at least one stock item.");
        }
        Set<UUID> seen = new HashSet<>();
        newLines.forEach(line -> {
            if (!seen.add(line.getStockItemId())) {
                throw InventoryRuleException.duplicateStockItem(line.getStockItemId());
            }
        });
        lines.clear();
        lines.addAll(newLines);
        reason = newReason == null || newReason.isBlank() ? null : newReason.strip();
    }

    public void confirm(UUID accountId, Instant now, LocalDate day) {
        requireStatus(PurchaseDocumentStatus.DRAFT, "be confirmed");
        status = PurchaseDocumentStatus.CONFIRMED;
        confirmedAt = now;
        confirmedByAccountId = accountId;
        businessDate = day;
    }

    public void cancel(Instant now) {
        requireStatus(PurchaseDocumentStatus.DRAFT, "be cancelled");
        status = PurchaseDocumentStatus.CANCELLED;
        cancelledAt = now;
    }

    private void requireStatus(PurchaseDocumentStatus required, String action) {
        if (status != required) {
            throw PurchasingConflictException.returnState(id, status, action);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public UUID getGoodsReceiptId() {
        return goodsReceiptId;
    }

    public String getReason() {
        return reason;
    }

    public PurchaseDocumentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public List<SupplierReturnLine> getLines() {
        return List.copyOf(lines);
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
