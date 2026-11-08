package com.xavier.backend.orders.delivery.service;

import com.xavier.backend.customers.CustomerSnapshot;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.orders.DeliverySlip;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.delivery.model.Delivery;
import com.xavier.backend.orders.delivery.model.DeliveryAddress;
import com.xavier.backend.orders.delivery.repository.DeliveryRepository;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.service.OrderLookup;
import com.xavier.backend.orders.service.OrderViews;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builds the courier's slip of a DELIVERY order (D129) in the caller's transaction. */
@Component
public class DeliverySlips {

    private final DeliveryRepository deliveries;
    private final OrderLookup lookup;
    private final OrderViews views;
    private final CustomersApi customers;

    DeliverySlips(DeliveryRepository deliveries, OrderLookup lookup, OrderViews views, CustomersApi customers) {
        this.deliveries = deliveries;
        this.lookup = lookup;
        this.views = views;
        this.customers = customers;
    }

    public DeliverySlip of(UUID orderId) {
        Delivery delivery = deliveries.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(DeliveryService.ENTITY, orderId));
        Order order = lookup.load(orderId);
        OrderBillingView billing = views.billing(order);
        DeliveryAddress address = delivery.address();
        String customerName = customers.findCustomer(delivery.getCustomerId()).map(CustomerSnapshot::name)
                .orElse(null);
        return new DeliverySlip(order.getOrderNumber(), customerName, delivery.getPhoneDigits(),
                new DeliverySlip.Address(address.line1(), address.line2(), address.city(), address.postalCode(),
                        address.notes()), delivery.getZoneCode(), delivery.getPromisedAt(),
                billing.lines().stream().map(line -> new DeliverySlip.Line(line.description(), line.netQty()))
                        .toList(), billing.due());
    }
}
