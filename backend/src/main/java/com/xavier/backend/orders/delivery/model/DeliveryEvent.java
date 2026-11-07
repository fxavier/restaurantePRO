package com.xavier.backend.orders.delivery.model;

import com.xavier.backend.orders.DeliveryStatus;
import com.xavier.backend.orders.delivery.model.dto.DeliveryEventView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * One transition of a delivery (D127; the delivery history of §4): a fact, append-only (database trigger). The opening
 * is the first, with no {@code fromStatus}.
 */
@Entity
@Immutable
@Table(schema = "orders", name = "delivery_event")
public class DeliveryEvent {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false, length = 20)
    private DeliveryStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private DeliveryStatus toStatus;

    @Column(nullable = false, updatable = false)
    private Instant at;

    @Column(nullable = false, updatable = false)
    private UUID actorEmployeeId;

    @Column(updatable = false)
    private UUID courierEmployeeId;

    @Column(updatable = false, length = 300)
    private String reason;

    protected DeliveryEvent() {
        // JPA
    }

    public static DeliveryEvent of(Delivery delivery, DeliveryStatus fromStatus, UUID actorEmployeeId, String reason,
                                   Instant at) {
        DeliveryEvent event = new DeliveryEvent();
        event.orderId = delivery.getOrderId();
        event.fromStatus = fromStatus;
        event.toStatus = delivery.getStatus();
        event.courierEmployeeId = delivery.getCourierEmployeeId();
        event.actorEmployeeId = Objects.requireNonNull(actorEmployeeId, "actorEmployeeId");
        event.reason = reason;
        event.at = Objects.requireNonNull(at, "at");
        return event;
    }

    public DeliveryEventView toView() {
        return new DeliveryEventView(id, fromStatus, toStatus, at, actorEmployeeId, courierEmployeeId, reason);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }
}
