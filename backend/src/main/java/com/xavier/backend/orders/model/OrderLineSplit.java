package com.xavier.backend.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/**
 * {@code quantity} units of a confirmed line moved to a new CONFIRMED line of the same order so that a discount can
 * apply to part of it (D105): a fact, insert only. The new line has no round, so production and stock never see it
 * twice. The split, its new line and the adjustment it was made for refer to each other; ids are drawn before insert.
 */
@Entity
@Immutable
@Table(schema = "orders", name = "order_line_split")
public class OrderLineSplit implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private UUID sourceLineId;

    @Column(nullable = false, updatable = false)
    private UUID targetLineId;

    @Column(nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, updatable = false)
    private UUID adjustmentId;

    @Column(nullable = false, updatable = false)
    private Instant at;

    @Column(nullable = false, updatable = false)
    private UUID employeeId;

    @Transient
    private boolean persisted;

    protected OrderLineSplit() {
        // JPA
        persisted = true;
    }

    public static OrderLineSplit of(UUID id, OrderLine source, OrderLine target, UUID adjustmentId, UUID employeeId,
                                    Instant now) {
        OrderLineSplit split = new OrderLineSplit();
        split.id = Objects.requireNonNull(id, "id");
        split.orderId = source.getOrderId();
        split.sourceLineId = Objects.requireNonNull(source.getId(), "source id");
        split.targetLineId = Objects.requireNonNull(target.getId(), "target id");
        split.quantity = target.getQuantity();
        split.adjustmentId = Objects.requireNonNull(adjustmentId, "adjustmentId");
        split.employeeId = Objects.requireNonNull(employeeId, "employeeId");
        split.at = Objects.requireNonNull(now, "now");
        return split;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    public UUID getSourceLineId() {
        return sourceLineId;
    }

    public UUID getTargetLineId() {
        return targetLineId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }
}
