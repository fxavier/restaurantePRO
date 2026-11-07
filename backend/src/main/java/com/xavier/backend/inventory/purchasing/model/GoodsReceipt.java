package com.xavier.backend.inventory.purchasing.model;

import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.purchasing.exception.PurchasingConflictException;
import com.xavier.backend.inventory.purchasing.exception.PurchasingRuleException;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A goods receipt of one warehouse (D125, P51; §8.3 machine {@code DRAFT → CONFIRMED}, {@code DRAFT → CANCELLED}),
 * optionally against a submitted purchase order, holding the supplier's document reference and invoiced costs.
 * Only its confirmation changes stock (§8.4). Lines are editable while DRAFT; one line per stock item, so the receipt
 * is one ledger source.
 */
@Entity
@Table(schema = "inventory", name = "goods_receipt")
public class GoodsReceipt {

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
    private UUID purchaseOrderId;

    private String supplierDocumentRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PurchaseDocumentStatus status;

    @Column(nullable = false)
    private Instant receivedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(updatable = false)
    private UUID createdByAccountId;

    private Instant confirmedAt;

    private UUID confirmedByAccountId;

    private LocalDate businessDate;

    private Instant cancelledAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(schema = "inventory", name = "goods_receipt_line", joinColumns = @JoinColumn(name = "receipt_id"))
    private List<GoodsReceiptLine> lines = new ArrayList<>();

    @Version
    private Long version;

    protected GoodsReceipt() {
        // JPA
    }

    public static GoodsReceipt draft(UUID storeId, UUID warehouseId, UUID supplierId, UUID purchaseOrderId,
                                     String supplierDocumentRef, Instant receivedAt, List<GoodsReceiptLine> lines,
                                     UUID accountId, Instant now) {
        GoodsReceipt receipt = new GoodsReceipt();
        receipt.storeId = storeId;
        receipt.warehouseId = warehouseId;
        receipt.supplierId = supplierId;
        receipt.purchaseOrderId = purchaseOrderId;
        receipt.status = PurchaseDocumentStatus.DRAFT;
        receipt.createdAt = now;
        receipt.createdByAccountId = accountId;
        receipt.edit(supplierDocumentRef, receivedAt == null ? now : receivedAt, lines);
        return receipt;
    }

    /** Replaces the document reference, arrival instant and lines of a DRAFT receipt. */
    public void edit(String newDocumentRef, Instant newReceivedAt, List<GoodsReceiptLine> newLines) {
        requireStatus(PurchaseDocumentStatus.DRAFT, "be edited");
        if (newLines.isEmpty()) {
            throw InventoryRuleException.invalidQuantity("A goods receipt receives at least one stock item.");
        }
        Set<UUID> seen = new HashSet<>();
        newLines.forEach(line -> {
            if (!seen.add(line.getStockItemId())) {
                throw InventoryRuleException.duplicateStockItem(line.getStockItemId());
            }
        });
        lines.clear();
        lines.addAll(newLines);
        supplierDocumentRef = newDocumentRef == null || newDocumentRef.isBlank() ? null : newDocumentRef.strip();
        receivedAt = newReceivedAt;
    }

    /** The CAS DRAFT → CONFIRMED (taken under the row lock): a receipt is confirmed, and posts, once. */
    public void confirm(UUID accountId, Instant now, LocalDate day) {
        requireStatus(PurchaseDocumentStatus.DRAFT, "be confirmed");
        status = PurchaseDocumentStatus.CONFIRMED;
        confirmedAt = now;
        confirmedByAccountId = accountId;
        businessDate = day;
    }

    /** DRAFT → CANCELLED: nothing was posted. */
    public void cancel(Instant now) {
        requireStatus(PurchaseDocumentStatus.DRAFT, "be cancelled");
        status = PurchaseDocumentStatus.CANCELLED;
        cancelledAt = now;
    }

    /** @throws PurchasingRuleException RECEIPT_NOT_CONFIRMED */
    public void requireConfirmed() {
        if (status != PurchaseDocumentStatus.CONFIRMED) {
            throw PurchasingRuleException.receiptNotConfirmed(id, status);
        }
    }

    public Optional<GoodsReceiptLine> line(UUID lineId) {
        return lines.stream().filter(line -> line.getId().equals(lineId)).findFirst();
    }

    private void requireStatus(PurchaseDocumentStatus required, String action) {
        if (status != required) {
            throw PurchasingConflictException.receiptState(id, status, action);
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

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public String getSupplierDocumentRef() {
        return supplierDocumentRef;
    }

    public PurchaseDocumentStatus getStatus() {
        return status;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public UUID getConfirmedByAccountId() {
        return confirmedByAccountId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public List<GoodsReceiptLine> getLines() {
        return List.copyOf(lines);
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
