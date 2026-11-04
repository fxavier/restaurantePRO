package com.xavier.backend.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A round of an order (spec §8.3 OrderBatch CONFIRMED → DISPATCHED). Its lines belong to it for good: a round is
 * never re-sent (§4). DISPATCHED means production has received it (D35); a repeated notice changes nothing.
 */
@Entity
@Table(schema = "orders", name = "order_batch")
public class OrderBatch {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private int batchNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BatchStatus status;

    @Column(nullable = false, updatable = false)
    private UUID terminalId;

    @Column(nullable = false, updatable = false)
    private Instant confirmedAt;

    @Column(nullable = false, updatable = false)
    private UUID confirmedByEmployeeId;

    @Column(nullable = false, updatable = false)
    private UUID releaseId;

    @Column(nullable = false, updatable = false)
    private long catalogVersion;

    private Instant dispatchedAt;

    protected OrderBatch() {
        // JPA
    }

    public static OrderBatch confirmed(UUID orderId, UUID storeId, int batchNo, UUID terminalId, UUID employeeId,
                                       UUID releaseId, long catalogVersion, Instant now) {
        OrderBatch batch = new OrderBatch();
        batch.orderId = Objects.requireNonNull(orderId, "orderId");
        batch.storeId = Objects.requireNonNull(storeId, "storeId");
        batch.batchNo = batchNo;
        batch.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        batch.confirmedByEmployeeId = Objects.requireNonNull(employeeId, "employeeId");
        batch.releaseId = Objects.requireNonNull(releaseId, "releaseId");
        batch.catalogVersion = catalogVersion;
        batch.confirmedAt = Objects.requireNonNull(now, "now");
        batch.status = BatchStatus.CONFIRMED;
        return batch;
    }

    /** CONFIRMED → DISPATCHED. @return {@code false} when it already was: nothing changed */
    public boolean markDispatched(Instant now) {
        if (status == BatchStatus.DISPATCHED) {
            return false;
        }
        status = BatchStatus.DISPATCHED;
        dispatchedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public int getBatchNo() {
        return batchNo;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public UUID getConfirmedByEmployeeId() {
        return confirmedByEmployeeId;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }
}
