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
 * A purchase order of one store and warehouse to one supplier (D124, §8.3 machine {@code DRAFT → SUBMITTED →
 * CLOSED}, DRAFT or SUBMITTED → CANCELLED). DRAFT lines are editable; a SUBMITTED order is immutable. How much has
 * arrived is derived from confirmed receipts ({@link ReceiptProgress}), never stored here. An order changes no
 * stock. CLOSED short-closes an order that will not be fully delivered; an order with a confirmed receipt is closed,
 * never cancelled.
 */
@Entity
@Table(schema = "inventory", name = "purchase_order")
public class PurchaseOrder {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(nullable = false, updatable = false)
    private UUID supplierId;

    @Column(nullable = false, updatable = false, length = 30)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PurchaseOrderStatus status;

    private LocalDate expectedAt;

    private String notes;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(updatable = false)
    private UUID createdByAccountId;

    private Instant submittedAt;

    private Instant closedAt;

    private Instant cancelledAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(schema = "inventory", name = "purchase_order_line",
            joinColumns = @JoinColumn(name = "purchase_order_id"))
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    @Version
    private Long version;

    protected PurchaseOrder() {
        // JPA
    }

    public static PurchaseOrder draft(UUID storeId, UUID warehouseId, UUID supplierId, String number,
                                      List<PurchaseOrderLine> lines, LocalDate expectedAt, String notes,
                                      UUID accountId, Instant now) {
        PurchaseOrder order = new PurchaseOrder();
        order.storeId = storeId;
        order.warehouseId = warehouseId;
        order.supplierId = supplierId;
        order.number = number.strip();
        order.status = PurchaseOrderStatus.DRAFT;
        order.createdAt = now;
        order.createdByAccountId = accountId;
        order.edit(lines, expectedAt, notes);
        return order;
    }

    /** Replaces the lines, expected date and notes of a DRAFT order; one line per stock item. */
    public void edit(List<PurchaseOrderLine> newLines, LocalDate newExpectedAt, String newNotes) {
        requireStatus(PurchaseOrderStatus.DRAFT, "be edited");
        if (newLines.isEmpty()) {
            throw InventoryRuleException.invalidQuantity("A purchase order orders at least one stock item.");
        }
        Set<UUID> seen = new HashSet<>();
        newLines.forEach(line -> {
            if (!seen.add(line.getStockItemId())) {
                throw InventoryRuleException.duplicateStockItem(line.getStockItemId());
            }
        });
        lines.clear();
        lines.addAll(newLines);
        expectedAt = newExpectedAt;
        notes = newNotes == null || newNotes.isBlank() ? null : newNotes.strip();
    }

    /** DRAFT → SUBMITTED: the lines are frozen and goods may be received against it. */
    public void submit(Instant now) {
        requireStatus(PurchaseOrderStatus.DRAFT, "be submitted");
        status = PurchaseOrderStatus.SUBMITTED;
        submittedAt = now;
    }

    /** SUBMITTED → CLOSED: short-closes an order that will not be (fully) delivered. */
    public void close(Instant now) {
        requireStatus(PurchaseOrderStatus.SUBMITTED, "be closed");
        status = PurchaseOrderStatus.CLOSED;
        closedAt = now;
    }

    /**
     * DRAFT or SUBMITTED → CANCELLED.
     *
     * @throws PurchasingRuleException PO_HAS_RECEIPTS when goods were already received against it (close it)
     */
    public void cancel(boolean hasConfirmedReceipts, Instant now) {
        if (status != PurchaseOrderStatus.DRAFT && status != PurchaseOrderStatus.SUBMITTED) {
            throw PurchasingConflictException.orderState(id, status, "be cancelled");
        }
        if (hasConfirmedReceipts) {
            throw PurchasingRuleException.hasReceipts(id);
        }
        status = PurchaseOrderStatus.CANCELLED;
        cancelledAt = now;
    }

    /** @throws PurchasingRuleException PO_NOT_SUBMITTED unless goods can be received against it */
    public void requireReceivable() {
        if (status != PurchaseOrderStatus.SUBMITTED) {
            throw PurchasingRuleException.notSubmitted(id, status);
        }
    }

    public Optional<PurchaseOrderLine> line(UUID lineId) {
        return lines.stream().filter(line -> line.getId().equals(lineId)).findFirst();
    }

    private void requireStatus(PurchaseOrderStatus required, String action) {
        if (status != required) {
            throw PurchasingConflictException.orderState(id, status, action);
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

    public String getNumber() {
        return number;
    }

    public PurchaseOrderStatus getStatus() {
        return status;
    }

    public LocalDate getExpectedAt() {
        return expectedAt;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public List<PurchaseOrderLine> getLines() {
        return List.copyOf(lines);
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
