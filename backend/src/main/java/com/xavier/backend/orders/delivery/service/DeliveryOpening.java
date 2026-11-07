package com.xavier.backend.orders.delivery.service;

import com.xavier.backend.customers.AddressSnapshot;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.orders.DeliveryStatus;
import com.xavier.backend.orders.delivery.exception.DeliveryRuleException;
import com.xavier.backend.orders.delivery.model.Delivery;
import com.xavier.backend.orders.delivery.model.DeliveryAddress;
import com.xavier.backend.orders.delivery.model.DeliveryEvent;
import com.xavier.backend.orders.delivery.model.DeliveryZone;
import com.xavier.backend.orders.delivery.model.dto.AddressRequest;
import com.xavier.backend.orders.delivery.model.dto.DeliveryRequest;
import com.xavier.backend.orders.delivery.repository.DeliveryEventRepository;
import com.xavier.backend.orders.delivery.repository.DeliveryRepository;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.service.OrderChanges;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The delivery half of opening a DELIVERY order (D127, D128), in the opening's transaction: everything is checked by
 * {@link #prepare} before the order is written, then {@link #open} writes the PENDING delivery, its first event and
 * the zone's fee draft.
 */
@Service
public class DeliveryOpening {

    private final DeliveryRepository deliveries;
    private final DeliveryEventRepository events;
    private final DeliveryZones zones;
    private final DeliveryFees fees;
    private final CustomersApi customers;
    private final OrderChanges changes;
    private final Clock clock;

    DeliveryOpening(DeliveryRepository deliveries, DeliveryEventRepository events, DeliveryZones zones,
                    DeliveryFees fees, CustomersApi customers, OrderChanges changes, Clock clock) {
        this.deliveries = deliveries;
        this.events = events;
        this.zones = zones;
        this.fees = fees;
        this.customers = customers;
        this.changes = changes;
        this.clock = clock;
    }

    /** What a delivery opens with, all checked. */
    public record Prepared(UUID customerId, String phoneDigits, DeliveryAddress address, DeliveryZone zone,
                           Instant promisedAt) {
    }

    /**
     * @throws DeliveryRuleException 422 {@code CUSTOMER_REQUIRED}, {@code ZONE_OTHER_STORE}, {@code ZONE_INACTIVE},
     *         {@code FEE_ITEM_NOT_PUBLISHED}; customers' 422 {@code CUSTOMER_INACTIVE}
     * @throws com.xavier.backend.shared.exception.NotFoundException 404 for an unknown zone, customer or address
     */
    public Prepared prepare(UUID storeId, DeliveryRequest request) {
        if (request == null || request.customerId() == null) {
            throw DeliveryRuleException.customerRequired();
        }
        DeliveryZone zone = zones.usable(request.zoneId(), storeId);
        customers.snapshot(request.customerId());
        DeliveryAddress address = address(request);
        fees.requirePublished(zone);
        return new Prepared(request.customerId(), customers.primaryPhone(request.customerId()).orElse(null), address,
                zone, request.promisedAt());
    }

    public Delivery open(Order order, Prepared prepared, UUID employeeId) {
        Delivery delivery = deliveries.saveAndFlush(Delivery.open(order.getId(), order.getStoreId(),
                prepared.customerId(), prepared.phoneDigits(), prepared.address(), prepared.zone(),
                prepared.promisedAt()));
        events.saveAndFlush(DeliveryEvent.of(delivery, null, employeeId, null, clock.instant()));
        fees.addTo(order, prepared.zone(), employeeId);
        Map<String, Object> details = new HashMap<>();
        details.put("orderId", order.getId().toString());
        details.put("zoneCode", delivery.getZoneCode());
        details.put("status", DeliveryStatus.PENDING.name());
        changes.record("DELIVERY_OPENED", DeliveryService.ENTITY, order.getId(), order.getStoreId(), order.getId(),
                details);
        return delivery;
    }

    private DeliveryAddress address(DeliveryRequest request) {
        if (request.address() != null) {
            AddressRequest typed = request.address();
            return new DeliveryAddress(typed.line1(), typed.line2(), typed.city(), typed.postalCode(),
                    request.notes() != null ? request.notes() : typed.notes());
        }
        AddressSnapshot saved = customers.address(request.customerId(), request.addressId())
                .orElseThrow(() -> new OrderNotFoundException("CustomerAddress", request.addressId()));
        return new DeliveryAddress(saved.line1(), saved.line2(), saved.city(), saved.postalCode(),
                request.notes() != null ? request.notes() : saved.notes());
    }
}
