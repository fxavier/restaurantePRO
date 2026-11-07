package com.xavier.backend.inventory.model;

import com.xavier.backend.inventory.exception.InventoryConflictException;
import com.xavier.backend.inventory.exception.InventoryRuleException;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A stock transfer from one warehouse to another (D120, §8.3 machine {@code DRAFT → SHIPPED → RECEIVED},
 * {@code DRAFT → CANCELLED}). Within one store, shipping receives everything at once; between stores the
 * destination receives later and records what did not arrive. Lines are editable only while DRAFT.
 */
@Entity
@Table(schema = "inventory", name = "stock_transfer")
public class StockTransfer {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID fromWarehouseId;

    @Column(nullable = false, updatable = false)
    private UUID toWarehouseId;

    @Column(nullable = false, updatable = false)
    private UUID fromStoreId;

    @Column(nullable = false, updatable = false)
    private UUID toStoreId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransferStatus status;

    private String notes;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(updatable = false)
    private UUID createdByActorId;

    private Instant shippedAt;

    private UUID shippedByActorId;

    private Instant receivedAt;

    private UUID receivedByActorId;

    private Instant cancelledAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(schema = "inventory", name = "stock_transfer_line",
            joinColumns = @JoinColumn(name = "transfer_id"))
    private List<TransferLine> lines = new ArrayList<>();

    @Version
    private Long version;

    protected StockTransfer() {
        // JPA
    }

    public static StockTransfer draft(UUID fromWarehouseId, UUID fromStoreId, UUID toWarehouseId, UUID toStoreId,
                                      List<TransferLine> lines, String notes, UUID actorId, Instant now) {
        if (fromWarehouseId.equals(toWarehouseId)) {
            throw InventoryRuleException.sameWarehouse(fromWarehouseId);
        }
        StockTransfer transfer = new StockTransfer();
        transfer.fromWarehouseId = fromWarehouseId;
        transfer.fromStoreId = fromStoreId;
        transfer.toWarehouseId = toWarehouseId;
        transfer.toStoreId = toStoreId;
        transfer.status = TransferStatus.DRAFT;
        transfer.createdAt = now;
        transfer.createdByActorId = actorId;
        transfer.edit(lines, notes);
        return transfer;
    }

    /** Replaces the lines and notes of a DRAFT transfer. */
    public void edit(List<TransferLine> newLines, String newNotes) {
        requireStatus(TransferStatus.DRAFT, "be edited");
        if (newLines.isEmpty()) {
            throw InventoryRuleException.invalidQuantity("A transfer moves at least one stock item.");
        }
        Set<UUID> seen = new HashSet<>();
        newLines.forEach(line -> {
            if (!seen.add(line.getStockItemId())) {
                throw InventoryRuleException.duplicateStockItem(line.getStockItemId());
            }
        });
        lines.clear();
        lines.addAll(newLines);
        notes = newNotes == null || newNotes.isBlank() ? null : newNotes.strip();
    }

    /** DRAFT → SHIPPED. The OUTs are posted next, then their costs are recorded ({@link #recordShippingCosts}). */
    public void ship(UUID actorId, Instant now) {
        requireStatus(TransferStatus.DRAFT, "be shipped");
        status = TransferStatus.SHIPPED;
        shippedAt = now;
        shippedByActorId = actorId;
    }

    /** The unit cost each line's OUT was valued at (the source average), which the destination uses (D120). */
    public void recordShippingCosts(Map<UUID, BigDecimal> unitCosts) {
        requireStatus(TransferStatus.SHIPPED, "record shipping costs");
        lines.forEach(line -> line.shippedAt(unitCosts.get(line.getStockItemId())));
    }

    /**
     * SHIPPED → RECEIVED with the quantity that arrived of every line (D120). A shortfall is what did not arrive.
     *
     * @throws InventoryRuleException RECEIVED_EXCEEDS_SHIPPED, or INVALID_QUANTITY when a line is missing, unknown
     *                                or negative
     */
    public void receive(Map<UUID, BigDecimal> received, UUID actorId, Instant now) {
        requireStatus(TransferStatus.SHIPPED, "be received");
        for (UUID item : received.keySet()) {
            if (lines.stream().noneMatch(line -> line.getStockItemId().equals(item))) {
                throw InventoryRuleException.invalidQuantity("Stock item " + item + " is not on transfer " + id
                        + ".");
            }
        }
        for (TransferLine line : lines) {
            BigDecimal quantity = received.get(line.getStockItemId());
            if (quantity == null || quantity.signum() < 0) {
                throw InventoryRuleException.invalidQuantity("Line " + line.getStockItemId() + " of transfer " + id
                        + " needs a received quantity of zero or more.");
            }
            if (quantity.compareTo(line.getQuantityShipped()) > 0) {
                throw InventoryRuleException.receivedExceedsShipped(line.getStockItemId(), line.getQuantityShipped());
            }
        }
        lines.forEach(line -> line.received(received.get(line.getStockItemId())));
        status = TransferStatus.RECEIVED;
        receivedAt = now;
        receivedByActorId = actorId;
    }

    /** DRAFT → CANCELLED. */
    public void cancel(Instant now) {
        requireStatus(TransferStatus.DRAFT, "be cancelled");
        status = TransferStatus.CANCELLED;
        cancelledAt = now;
    }

    public boolean withinOneStore() {
        return fromStoreId.equals(toStoreId);
    }

    private void requireStatus(TransferStatus required, String action) {
        if (status != required) {
            throw InventoryConflictException.transferState(id, status, action);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getFromWarehouseId() {
        return fromWarehouseId;
    }

    public UUID getToWarehouseId() {
        return toWarehouseId;
    }

    public UUID getFromStoreId() {
        return fromStoreId;
    }

    public UUID getToStoreId() {
        return toStoreId;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public List<TransferLine> getLines() {
        return List.copyOf(lines);
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
