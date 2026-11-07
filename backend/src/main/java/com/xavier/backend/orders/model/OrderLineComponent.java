package com.xavier.backend.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A chosen component of a MENU line (D108): unpriced, for production routing and stock. A draft menu carries its
 * choices; confirmation {@link #freeze freezes} them with the code, description and destination of the release the
 * menu is priced from. {@code quantity} is per menu unit (the option's quantity); the line's quantity multiplies it.
 * Immutable once its line is CONFIRMED (trigger {@code order_line_component_confirmed_immutable}).
 */
@Entity
@Table(schema = "orders", name = "order_line_component")
public class OrderLineComponent {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private UUID lineId;

    @Column(nullable = false, updatable = false, length = 30)
    private String groupCode;

    @Column(nullable = false, updatable = false)
    private UUID itemId;

    @Column(updatable = false)
    private UUID variantId;

    @Column(length = 30)
    private String itemCode;

    @Column(length = 120)
    private String description;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(length = 30)
    private String destination;

    @Column(nullable = false, updatable = false)
    private int position;

    protected OrderLineComponent() {
        // JPA
    }

    public static OrderLineComponent chosen(OrderLine line, String groupCode, UUID itemId, UUID variantId,
                                            int position) {
        OrderLineComponent component = new OrderLineComponent();
        component.orderId = line.getOrderId();
        component.lineId = Objects.requireNonNull(line.getId(), "line id");
        component.groupCode = Objects.requireNonNull(groupCode, "groupCode");
        component.itemId = Objects.requireNonNull(itemId, "itemId");
        component.variantId = variantId;
        component.quantity = BigDecimal.ONE.setScale(3);
        component.position = position;
        return component;
    }

    /** At confirmation: what the menu's release says of this component; {@code quantity} is the option's. */
    public void freeze(String itemCode, String description, BigDecimal quantity, String destination) {
        this.itemCode = Objects.requireNonNull(itemCode, "itemCode");
        this.description = Objects.requireNonNull(description, "description");
        this.quantity = OrderLine.validQuantity(quantity);
        this.destination = destination;
    }

    public UUID getId() {
        return id;
    }

    public UUID getLineId() {
        return lineId;
    }

    public String getGroupCode() {
        return groupCode;
    }

    public UUID getItemId() {
        return itemId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public String getItemCode() {
        return itemCode;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public String getDestination() {
        return destination;
    }

    public int getPosition() {
        return position;
    }
}
