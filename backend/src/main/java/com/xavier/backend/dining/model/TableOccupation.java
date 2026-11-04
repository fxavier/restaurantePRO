package com.xavier.backend.dining.model;

import com.xavier.backend.dining.exception.IllegalOccupationStateException;
import com.xavier.backend.dining.exception.InvalidDiningSetupException;
import com.xavier.backend.dining.model.dto.CloseReason;
import com.xavier.backend.dining.model.dto.OccupationView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One order's use of a table (CONTEXT.md: Ocupação, spec §8.1, §8.3 OPEN → CLOSED). The table is FREE when it has
 * no open occupation. A closed occupation is history and never changes again; a table move closes it as
 * {@code MOVED} and the party continues in a new occupation on the target table.
 */
@Entity
@Table(schema = "dining", name = "table_occupation")
public class TableOccupation {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID tableId;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private int guests;

    @Column(nullable = false, updatable = false)
    private Instant openedAt;

    @Column(nullable = false, updatable = false)
    private UUID openedByEmployeeId;

    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CloseReason closeReason;

    private Instant subtotalPrintedAt;

    @Version
    private Long version;

    protected TableOccupation() {
        // JPA
    }

    public static TableOccupation open(UUID storeId, UUID tableId, UUID orderId, int guests, UUID employeeId,
                                       Instant now) {
        if (guests < 1) {
            throw new InvalidDiningSetupException("guests must be at least 1");
        }
        TableOccupation occupation = new TableOccupation();
        occupation.storeId = storeId;
        occupation.tableId = tableId;
        occupation.orderId = orderId;
        occupation.guests = guests;
        occupation.openedAt = now;
        occupation.openedByEmployeeId = employeeId;
        return occupation;
    }

    public void close(CloseReason reason, Instant now) {
        requireOpen();
        this.closedAt = now;
        this.closeReason = reason;
    }

    public void markSubtotalPrinted(Instant now) {
        requireOpen();
        this.subtotalPrintedAt = now;
    }

    /**
     * Closes this occupation as {@code MOVED} and returns the party's occupation of {@code targetTableId}. The party
     * keeps its seating time, opening employee, guests and subtotal mark: only the table changes.
     */
    public TableOccupation moveTo(UUID targetTableId, Instant now) {
        close(CloseReason.MOVED, now);
        TableOccupation moved = open(storeId, targetTableId, orderId, guests, openedByEmployeeId, openedAt);
        moved.subtotalPrintedAt = subtotalPrintedAt;
        return moved;
    }

    public boolean isOpen() {
        return closedAt == null;
    }

    public OccupationView toView() {
        return new OccupationView(id, storeId, tableId, orderId, guests, openedAt, openedByEmployeeId,
                subtotalPrintedAt);
    }

    private void requireOpen() {
        if (!isOpen()) {
            throw IllegalOccupationStateException.closed(id);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getTableId() {
        return tableId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public CloseReason getCloseReason() {
        return closeReason;
    }
}
