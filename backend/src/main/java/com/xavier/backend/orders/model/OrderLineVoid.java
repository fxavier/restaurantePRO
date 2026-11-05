package com.xavier.backend.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * A void of part or all of a confirmed line (r2-decisions §4 orders): a fact, insert only (also in the database). It
 * names the operator and the approver who authorized it (spec §3, D28, D40), its reason, and whether the goods were
 * wasted (input for inventory, R4).
 */
@Entity
@Immutable
@Table(schema = "orders", name = "order_line_void")
public class OrderLineVoid {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private UUID lineId;

    @Column(nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, updatable = false, length = 200)
    private String reason;

    @Column(nullable = false, updatable = false)
    private boolean wasted;

    @Column(nullable = false, updatable = false)
    private UUID operatorEmployeeId;

    @Column(nullable = false, updatable = false)
    private UUID approverEmployeeId;

    @Column(nullable = false, updatable = false)
    private UUID terminalId;

    @Column(nullable = false, updatable = false)
    private Instant voidedAt;

    protected OrderLineVoid() {
        // JPA
    }

    /** {@code line} has been checked to hold {@code quantity} ({@link OrderLine#requireAvailable}). */
    public static OrderLineVoid of(OrderLine line, BigDecimal quantity, String reason, boolean wasted,
                                   UUID operatorEmployeeId, UUID approverEmployeeId, UUID terminalId, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A void needs a reason");
        }
        OrderLineVoid lineVoid = new OrderLineVoid();
        lineVoid.orderId = line.getOrderId();
        lineVoid.lineId = line.getId();
        lineVoid.quantity = OrderLine.validQuantity(quantity);
        lineVoid.reason = reason.strip();
        lineVoid.wasted = wasted;
        lineVoid.operatorEmployeeId = Objects.requireNonNull(operatorEmployeeId, "operatorEmployeeId");
        lineVoid.approverEmployeeId = Objects.requireNonNull(approverEmployeeId, "approverEmployeeId");
        lineVoid.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        lineVoid.voidedAt = Objects.requireNonNull(now, "now");
        return lineVoid;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getLineId() {
        return lineId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public String getReason() {
        return reason;
    }

    public boolean isWasted() {
        return wasted;
    }

    public UUID getOperatorEmployeeId() {
        return operatorEmployeeId;
    }

    public UUID getApproverEmployeeId() {
        return approverEmployeeId;
    }

    public Instant getVoidedAt() {
        return voidedAt;
    }
}
