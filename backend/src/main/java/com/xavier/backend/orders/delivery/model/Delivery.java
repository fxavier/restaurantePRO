package com.xavier.backend.orders.delivery.model;

import com.xavier.backend.orders.DeliveryStatus;
import com.xavier.backend.orders.DeliverySummary;
import com.xavier.backend.orders.delivery.exception.DeliveryConflictException;
import com.xavier.backend.orders.delivery.exception.DeliveryRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The delivery of a DELIVERY order (D127): one per order, keyed by it. Its address and zone are snapshots, editable
 * while PENDING and frozen from the first dispatch. Its status is its own: no transition here touches the order's
 * lifecycle, billing lock or fiscal state (§8.3). {@code @Version} decides a race between two transitions (D133).
 * Each transition returns the status it left, for the {@link DeliveryEvent} the service writes.
 */
@Entity
@Table(schema = "orders", name = "delivery")
public class Delivery {

    @Id
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Column(updatable = false, length = 20)
    private String phoneDigits;

    @Column(name = "address_line1", nullable = false, length = 200)
    private String addressLine1;

    @Column(name = "address_line2", length = 200)
    private String addressLine2;

    @Column(length = 100)
    private String city;

    @Column(length = 20)
    private String postalCode;

    @Column(columnDefinition = "text")
    private String addressNotes;

    @Column(nullable = false)
    private UUID zoneId;

    @Column(nullable = false, length = 20)
    private String zoneCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status;

    private UUID courierEmployeeId;

    private Instant promisedAt;

    private Instant dispatchedAt;

    private Instant deliveredAt;

    @Version
    private Long version;

    protected Delivery() {
        // JPA
    }

    public static Delivery open(UUID orderId, UUID storeId, UUID customerId, String phoneDigits,
                                DeliveryAddress address, DeliveryZone zone, Instant promisedAt) {
        Delivery delivery = new Delivery();
        delivery.orderId = Objects.requireNonNull(orderId, "orderId");
        delivery.storeId = Objects.requireNonNull(storeId, "storeId");
        delivery.customerId = Objects.requireNonNull(customerId, "customerId");
        delivery.phoneDigits = phoneDigits;
        delivery.status = DeliveryStatus.PENDING;
        delivery.moveTo(Objects.requireNonNull(address, "address"));
        delivery.zoneIn(Objects.requireNonNull(zone, "zone"));
        delivery.promisedAt = promisedAt;
        return delivery;
    }

    /**
     * Changes what is given (null leaves it) while PENDING.
     *
     * @throws DeliveryRuleException {@code ADDRESS_FROZEN} once dispatched
     */
    public void edit(DeliveryAddress address, DeliveryZone zone, Instant promisedAt) {
        if (status != DeliveryStatus.PENDING) {
            throw DeliveryRuleException.addressFrozen(orderId);
        }
        if (address != null) {
            moveTo(address);
        }
        if (zone != null) {
            zoneIn(zone);
        }
        if (promisedAt != null) {
            this.promisedAt = promisedAt;
        }
    }

    /** PENDING or RETURNED (a retry) → OUT_FOR_DELIVERY with {@code courierEmployeeId}. */
    public DeliveryStatus dispatch(UUID courierEmployeeId, Instant now) {
        DeliveryStatus from = leave(Set.of(DeliveryStatus.PENDING, DeliveryStatus.RETURNED), "dispatched");
        this.courierEmployeeId = Objects.requireNonNull(courierEmployeeId, "courierEmployeeId");
        dispatchedAt = Objects.requireNonNull(now, "now");
        status = DeliveryStatus.OUT_FOR_DELIVERY;
        return from;
    }

    /** OUT_FOR_DELIVERY → DELIVERED. */
    public DeliveryStatus markDelivered(Instant now) {
        DeliveryStatus from = leave(Set.of(DeliveryStatus.OUT_FOR_DELIVERY), "delivered");
        deliveredAt = Objects.requireNonNull(now, "now");
        status = DeliveryStatus.DELIVERED;
        return from;
    }

    /** OUT_FOR_DELIVERY → RETURNED: then a retry or a cancel (with voids). The courier stays as the last one. */
    public DeliveryStatus markReturned() {
        DeliveryStatus from = leave(Set.of(DeliveryStatus.OUT_FOR_DELIVERY), "returned");
        status = DeliveryStatus.RETURNED;
        return from;
    }

    /**
     * PENDING or RETURNED → CANCELLED, with its order.
     *
     * @throws DeliveryRuleException {@code DELIVERY_IN_PROGRESS} from any other status
     */
    public DeliveryStatus cancel() {
        if (status != DeliveryStatus.PENDING && status != DeliveryStatus.RETURNED) {
            throw DeliveryRuleException.inProgress(orderId);
        }
        DeliveryStatus from = status;
        status = DeliveryStatus.CANCELLED;
        return from;
    }

    private DeliveryStatus leave(Set<DeliveryStatus> allowed, String action) {
        if (!allowed.contains(status)) {
            throw DeliveryConflictException.state(orderId, status, action);
        }
        return status;
    }

    private void moveTo(DeliveryAddress address) {
        addressLine1 = address.line1().strip();
        addressLine2 = address.line2();
        city = address.city();
        postalCode = address.postalCode();
        addressNotes = address.notes();
    }

    private void zoneIn(DeliveryZone zone) {
        zoneId = zone.getId();
        zoneCode = zone.getCode();
    }

    public DeliveryAddress address() {
        return new DeliveryAddress(addressLine1, addressLine2, city, postalCode, addressNotes);
    }

    public DeliverySummary summary() {
        return new DeliverySummary(status, courierEmployeeId, promisedAt, zoneCode);
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getPhoneDigits() {
        return phoneDigits;
    }

    public UUID getZoneId() {
        return zoneId;
    }

    public String getZoneCode() {
        return zoneCode;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public UUID getCourierEmployeeId() {
        return courierEmployeeId;
    }

    public Instant getPromisedAt() {
        return promisedAt;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
