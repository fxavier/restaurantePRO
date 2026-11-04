package com.xavier.backend.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * One line of a transfer: {@code quantity} taken from a confirmed source line and given to its confirmed copy on the
 * target order. Its orders are always the transfer's own (also a database foreign key).
 */
@Entity
@Immutable
@Table(schema = "orders", name = "order_transfer_line")
public class OrderTransferLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID transferId;

    @Column(nullable = false, updatable = false)
    private UUID sourceOrderId;

    @Column(nullable = false, updatable = false)
    private UUID targetOrderId;

    @Column(nullable = false, updatable = false)
    private UUID sourceLineId;

    @Column(nullable = false, updatable = false)
    private UUID targetLineId;

    @Column(nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    protected OrderTransferLine() {
        // JPA
    }

    /** {@code copy} is {@code OrderLine.transferredCopy(source, …)} for this transfer. */
    public static OrderTransferLine of(OrderTransfer transfer, OrderLine source, OrderLine copy) {
        if (!source.getOrderId().equals(transfer.getSourceOrderId())
                || !copy.getOrderId().equals(transfer.getTargetOrderId())
                || !transfer.getId().equals(copy.getOriginTransferId())) {
            throw new IllegalArgumentException("The lines are not of transfer " + transfer.getId());
        }
        OrderTransferLine line = new OrderTransferLine();
        line.transferId = transfer.getId();
        line.sourceOrderId = transfer.getSourceOrderId();
        line.targetOrderId = transfer.getTargetOrderId();
        line.sourceLineId = source.getId();
        line.targetLineId = copy.getId();
        line.quantity = copy.getQuantity();
        return line;
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
