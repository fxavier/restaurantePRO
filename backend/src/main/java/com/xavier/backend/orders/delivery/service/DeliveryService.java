package com.xavier.backend.orders.delivery.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.identity.EmployeeDirectory;
import com.xavier.backend.orders.DeliveryStatus;
import com.xavier.backend.orders.delivery.exception.DeliveryRuleException;
import com.xavier.backend.orders.delivery.model.Delivery;
import com.xavier.backend.orders.delivery.model.DeliveryAddress;
import com.xavier.backend.orders.delivery.model.DeliveryEvent;
import com.xavier.backend.orders.delivery.model.DeliveryZone;
import com.xavier.backend.orders.delivery.model.dto.AddressRequest;
import com.xavier.backend.orders.delivery.model.dto.DeliveredRequest;
import com.xavier.backend.orders.delivery.model.dto.DeliveryUpdateRequest;
import com.xavier.backend.orders.delivery.model.dto.DeliveryView;
import com.xavier.backend.orders.delivery.model.dto.DispatchRequest;
import com.xavier.backend.orders.delivery.model.dto.ReturnedRequest;
import com.xavier.backend.orders.delivery.repository.DeliveryEventRepository;
import com.xavier.backend.orders.delivery.repository.DeliveryRepository;
import com.xavier.backend.orders.exception.OrderConflictException;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.OrderLine;
import com.xavier.backend.orders.model.event.DeliveryDispatched;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.repository.OrderBatchRepository;
import com.xavier.backend.orders.repository.OrderLineRepository;
import com.xavier.backend.orders.repository.OrderRepository;
import com.xavier.backend.orders.service.OrderChanges;
import com.xavier.backend.orders.service.OrderLookup;
import com.xavier.backend.orders.service.OrderViews;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The POS commands on a delivery (D127), each at the delivery's version (a race between two is decided by its
 * {@code @Version}, D133), each writing a {@link DeliveryEvent}, audited and notifying {@code ORDER}. None changes the
 * order: its lifecycle, billing lock, fiscal state and version are independent of the delivery (§8.3).
 */
@Service
public class DeliveryService {

    static final String ENTITY = "Delivery";

    private final DeliveryRepository deliveries;
    private final DeliveryEventRepository events;
    private final DeliveryZones zones;
    private final DeliveryFees fees;
    private final DeliveryViews views;
    private final OrderLookup lookup;
    private final OrderRepository orderRows;
    private final OrderViews orderViews;
    private final OrderLineRepository lines;
    private final OrderBatchRepository batches;
    private final EmployeeDirectory employees;
    private final AccessPolicy accessPolicy;
    private final OrderChanges changes;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    DeliveryService(DeliveryRepository deliveries, DeliveryEventRepository events,
                    DeliveryZones zones, DeliveryFees fees,
                    DeliveryViews views, OrderLookup lookup, OrderRepository orderRows, OrderViews orderViews,
                    OrderLineRepository lines, OrderBatchRepository batches,
                    EmployeeDirectory employees, AccessPolicy accessPolicy, OrderChanges changes,
                    ApplicationEventPublisher publisher, Clock clock) {
        this.deliveries = deliveries;
        this.events = events;
        this.zones = zones;
        this.fees = fees;
        this.views = views;
        this.lookup = lookup;
        this.orderRows = orderRows;
        this.orderViews = orderViews;
        this.lines = lines;
        this.batches = batches;
        this.employees = employees;
        this.accessPolicy = accessPolicy;
        this.changes = changes;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Changes the address, zone or promise of a PENDING delivery. A new zone replaces the order's fee draft by its own
     * fee. A confirmed fee is a fact: while it has quantity left, the zone does not change (422
     * {@code ZONE_FEE_CONFIRMED}); once it is voided, the new zone's fee is added as a draft.
     *
     * @throws DeliveryRuleException 422 {@code ADDRESS_FROZEN} once dispatched, {@code ZONE_OTHER_STORE},
     *         {@code ZONE_INACTIVE}, {@code FEE_ITEM_NOT_PUBLISHED}, {@code ZONE_FEE_CONFIRMED}
     */
    @Transactional
    public DeliveryView update(UUID orderId, DeliveryUpdateRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.DELIVERY_OPERATE);
        Delivery delivery = inSessionStore(pos, orderId, request.version());
        DeliveryZone zone = request.zoneId() == null ? null : zones.usable(request.zoneId(), pos.storeId());
        UUID previousZone = delivery.getZoneId();
        if (zone != null && !zone.getId().equals(previousZone)) {
            fees.requirePublished(zone);
            requireNoLiveConfirmedFee(orderId);
        }
        delivery.edit(address(request.address()), zone, request.promisedAt());
        delivery = deliveries.saveAndFlush(delivery);
        if (zone != null && !zone.getId().equals(previousZone)) {
            fees.replaceDraft(lookup.load(orderId), zone, pos.employeeId());
        }
        changed("DELIVERY_CHANGED", delivery, Map.of());
        return views.of(delivery);
    }

    /**
     * PENDING or RETURNED → OUT_FOR_DELIVERY: no drafts left, at least one confirmed round, and a courier who is an
     * active employee of the store. Publishes {@link DeliveryDispatched}, so production prints the slip.
     *
     * @throws DeliveryRuleException 422 {@code DELIVERY_HAS_DRAFTS}, {@code DELIVERY_NOTHING_CONFIRMED},
     *         {@code COURIER_NOT_IN_STORE}
     */
    @Transactional
    public DeliveryView dispatch(UUID orderId, DispatchRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.DELIVERY_OPERATE);
        Delivery delivery = inSessionStore(pos, orderId, request.version());
        // D132: the order row before the delivery row. FOR UPDATE waits for a draft being added (its foreign key
        // holds FOR KEY SHARE on the order) and for a round, so the drafts read next are the committed ones. It
        // does not move the order's version: a delivery never changes the order (§8.3).
        orderRows.lockForDispatch(orderId);
        Order order = lookup.load(orderId);
        if (order.getLifecycle() != OrderLifecycle.OPEN) {
            throw OrderConflictException.notOpen(orderId);
        }
        if (lines.findByOrderIdOrderById(orderId).stream().anyMatch(OrderLine::isDraft)) {
            throw DeliveryRuleException.hasDrafts(orderId);
        }
        if (batches.findByOrderIdOrderByBatchNo(orderId).isEmpty()) {
            throw DeliveryRuleException.nothingConfirmed(orderId);
        }
        if (!employees.isActiveInStore(request.courierEmployeeId(), delivery.getStoreId())) {
            throw DeliveryRuleException.courierNotInStore(request.courierEmployeeId());
        }
        DeliveryStatus from = delivery.dispatch(request.courierEmployeeId(), clock.instant());
        DeliveryEvent event = transitioned(delivery, from, pos, null);
        publisher.publishEvent(new DeliveryDispatched(orderId, event.getId(), delivery.getStoreId(),
                pos.terminalId()));
        return views.of(delivery);
    }

    /** OUT_FOR_DELIVERY → DELIVERED. The order stays OPEN until settlement closes it. */
    @Transactional
    public DeliveryView delivered(UUID orderId, DeliveredRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.DELIVERY_OPERATE);
        Delivery delivery = inSessionStore(pos, orderId, request.version());
        DeliveryStatus from = delivery.markDelivered(clock.instant());
        transitioned(delivery, from, pos, null);
        return views.of(delivery);
    }

    /** OUT_FOR_DELIVERY → RETURNED, with why: then a retry, or voids and a cancel. */
    @Transactional
    public DeliveryView returned(UUID orderId, ReturnedRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.DELIVERY_OPERATE);
        Delivery delivery = inSessionStore(pos, orderId, request.version());
        DeliveryStatus from = delivery.markReturned();
        transitioned(delivery, from, pos, request.reason().strip());
        return views.of(delivery);
    }

    /**
     * An order never carries two fees: a confirmed fee with net quantity left blocks a zone change until it is voided.
     *
     * @throws DeliveryRuleException 422 {@code ZONE_FEE_CONFIRMED}
     */
    private void requireNoLiveConfirmedFee(UUID orderId) {
        Map<UUID, java.math.BigDecimal> deducted = orderViews.deductions(List.of(orderId));
        boolean live = lines.findByOrderIdOrderById(orderId).stream()
                .filter(line -> !line.isDraft() && line.isAutomaticFee())
                .anyMatch(line -> line.getQuantity().compareTo(
                        deducted.getOrDefault(line.getId(), java.math.BigDecimal.ZERO)) > 0);
        if (live) {
            throw DeliveryRuleException.zoneFeeConfirmed(orderId);
        }
    }

    /** D42 (b): the delivery's store only (404), the session's store (403), then the delivery at the client's version. */
    private Delivery inSessionStore(PosContext pos, UUID orderId, long version) {
        UUID storeId = deliveries.findStoreIdByOrderId(orderId)
                .orElseThrow(() -> new OrderNotFoundException(ENTITY, orderId));
        accessPolicy.requireSessionStore(pos, storeId, ENTITY, orderId);
        Delivery delivery = deliveries.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(ENTITY, orderId));
        OptimisticLocking.requireVersion(Delivery.class, orderId, delivery.getVersion(), version);
        return delivery;
    }

    private DeliveryEvent transitioned(Delivery delivery, DeliveryStatus from, PosContext pos, String reason) {
        deliveries.saveAndFlush(delivery);
        DeliveryEvent event = events.saveAndFlush(DeliveryEvent.of(delivery, from, pos.employeeId(), reason,
                clock.instant()));
        Map<String, Object> details = new HashMap<>();
        details.put("from", from.name());
        details.put("to", delivery.getStatus().name());
        if (delivery.getCourierEmployeeId() != null) {
            details.put("courierEmployeeId", delivery.getCourierEmployeeId().toString());
        }
        if (reason != null) {
            details.put("reason", reason);
        }
        changed("DELIVERY_" + delivery.getStatus().name(), delivery, details);
        return event;
    }

    private void changed(String action, Delivery delivery, Map<String, Object> details) {
        Map<String, Object> all = new HashMap<>(details);
        all.put("orderId", delivery.getOrderId().toString());
        all.put("version", delivery.getVersion());
        changes.record(action, ENTITY, delivery.getOrderId(), delivery.getStoreId(), delivery.getOrderId(), all);
    }

    private static DeliveryAddress address(AddressRequest request) {
        return request == null ? null : new DeliveryAddress(request.line1(), request.line2(), request.city(),
                request.postalCode(), request.notes());
    }
}
