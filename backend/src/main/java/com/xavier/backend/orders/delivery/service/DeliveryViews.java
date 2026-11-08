package com.xavier.backend.orders.delivery.service;

import com.xavier.backend.orders.delivery.model.Delivery;
import com.xavier.backend.orders.delivery.model.DeliveryAddress;
import com.xavier.backend.orders.delivery.model.DeliveryEvent;
import com.xavier.backend.orders.delivery.model.dto.AddressRequest;
import com.xavier.backend.orders.delivery.model.dto.DeliveryEventView;
import com.xavier.backend.orders.delivery.model.dto.DeliveryView;
import com.xavier.backend.orders.delivery.repository.DeliveryEventRepository;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.repository.OrderRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Deliveries with their order number and history, in one read of each. */
@Component
public class DeliveryViews {

    private final DeliveryEventRepository events;
    private final OrderRepository orders;

    DeliveryViews(DeliveryEventRepository events, OrderRepository orders) {
        this.events = events;
        this.orders = orders;
    }

    public DeliveryView of(Delivery delivery) {
        return of(List.of(delivery)).getFirst();
    }

    public List<DeliveryView> of(List<Delivery> deliveries) {
        if (deliveries.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = deliveries.stream().map(Delivery::getOrderId).toList();
        Map<UUID, Long> numbers = orders.findAllById(ids).stream()
                .collect(Collectors.toMap(Order::getId, Order::getOrderNumber));
        Map<UUID, List<DeliveryEventView>> history = events.findByOrderIdInOrderById(ids).stream()
                .collect(Collectors.groupingBy(DeliveryEvent::getOrderId,
                        Collectors.mapping(DeliveryEvent::toView, Collectors.toList())));
        return deliveries.stream().map(delivery -> {
            DeliveryAddress address = delivery.address();
            return new DeliveryView(delivery.getOrderId(), numbers.get(delivery.getOrderId()), delivery.getStoreId(),
                    delivery.getCustomerId(), delivery.getPhoneDigits(), new AddressRequest(address.line1(),
                    address.line2(), address.city(), address.postalCode(), address.notes()), delivery.getZoneId(),
                    delivery.getZoneCode(), delivery.getStatus(), delivery.getCourierEmployeeId(),
                    delivery.getPromisedAt(), delivery.getDispatchedAt(), delivery.getDeliveredAt(),
                    delivery.getVersion(), history.getOrDefault(delivery.getOrderId(), List.of()));
        }).toList();
    }
}
