package com.xavier.backend.orders.delivery.service;

import com.xavier.backend.orders.DeliveryStatus;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.orders.delivery.exception.DeliveryRuleException;
import com.xavier.backend.orders.delivery.model.Delivery;
import com.xavier.backend.orders.delivery.model.DeliveryEvent;
import com.xavier.backend.orders.delivery.repository.DeliveryEventRepository;
import com.xavier.backend.orders.delivery.repository.DeliveryRepository;
import com.xavier.backend.orders.model.Order;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What the order's own close and cancel ask of its delivery (D127), in their transaction. Any other order mode passes
 * straight through.
 */
@Component
public class DeliveryGuards {

    private final DeliveryRepository deliveries;
    private final DeliveryEventRepository events;
    private final Clock clock;

    DeliveryGuards(DeliveryRepository deliveries, DeliveryEventRepository events, Clock clock) {
        this.deliveries = deliveries;
        this.events = events;
        this.clock = clock;
    }

    /** @throws DeliveryRuleException 422 {@code DELIVERY_NOT_DELIVERED} for a DELIVERY order not yet DELIVERED */
    public void requireClosable(Order order) {
        if (order.getMode() == OrderMode.DELIVERY
                && delivery(order).getStatus() != DeliveryStatus.DELIVERED) {
            throw DeliveryRuleException.notDelivered(order.getId());
        }
    }

    /**
     * The order is being cancelled: its delivery goes PENDING or RETURNED → CANCELLED, at its version (a concurrent
     * transition makes this a 409, D133).
     *
     * @throws DeliveryRuleException 422 {@code DELIVERY_IN_PROGRESS} when it is out or delivered
     */
    public void cancelWith(Order order, UUID employeeId, String reason) {
        if (order.getMode() != OrderMode.DELIVERY) {
            return;
        }
        Delivery delivery = delivery(order);
        DeliveryStatus from = delivery.cancel();
        deliveries.saveAndFlush(delivery);
        events.saveAndFlush(DeliveryEvent.of(delivery, from, employeeId, reason, clock.instant()));
    }

    private Delivery delivery(Order order) {
        return deliveries.findById(order.getId()).orElseThrow(() -> new IllegalStateException(
                "DELIVERY order " + order.getId() + " has no delivery"));
    }
}
