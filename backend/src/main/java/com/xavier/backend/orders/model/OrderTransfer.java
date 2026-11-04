package com.xavier.backend.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * A transfer of confirmed quantities between two OPEN orders of one store (spec §4, §8.2): a fact, insert only. It
 * moves the right to charge; its lines ({@link OrderTransferLine}) say what moved.
 */
@Entity
@Immutable
@Table(schema = "orders", name = "order_transfer")
public class OrderTransfer {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID sourceOrderId;

    @Column(nullable = false, updatable = false)
    private UUID targetOrderId;

    @Column(nullable = false, updatable = false)
    private UUID employeeId;

    @Column(nullable = false, updatable = false)
    private UUID terminalId;

    @Column(nullable = false, updatable = false)
    private Instant transferredAt;

    protected OrderTransfer() {
        // JPA
    }

    /** @throws IllegalArgumentException when the orders are one, or of two stores (the caller checked both) */
    public static OrderTransfer between(Order source, Order target, UUID employeeId, UUID terminalId, Instant now) {
        if (source.getId().equals(target.getId()) || !source.getStoreId().equals(target.getStoreId())) {
            throw new IllegalArgumentException("A transfer is between two orders of one store");
        }
        OrderTransfer transfer = new OrderTransfer();
        transfer.storeId = source.getStoreId();
        transfer.sourceOrderId = source.getId();
        transfer.targetOrderId = target.getId();
        transfer.employeeId = Objects.requireNonNull(employeeId, "employeeId");
        transfer.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        transfer.transferredAt = Objects.requireNonNull(now, "now");
        return transfer;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceOrderId() {
        return sourceOrderId;
    }

    public UUID getTargetOrderId() {
        return targetOrderId;
    }

    public Instant getTransferredAt() {
        return transferredAt;
    }
}
