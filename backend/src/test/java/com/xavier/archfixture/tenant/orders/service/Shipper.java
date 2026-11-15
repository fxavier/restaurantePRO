package com.xavier.archfixture.tenant.orders.service;

import com.xavier.archfixture.tenant.orders.model.event.LateTenant;
import com.xavier.archfixture.tenant.orders.model.event.OrderPacked;
import com.xavier.archfixture.tenant.orders.model.event.OrderShipped;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;

/** Fixture: publishes one event without a tenant, one with it last and one with it first. */
public class Shipper {

    private final ApplicationEventPublisher events;

    public Shipper(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void ship(UUID tenant, UUID order, UUID store) {
        events.publishEvent(new OrderShipped(order, store));
        events.publishEvent(new LateTenant(order, tenant));
    }

    public void pack(UUID tenant, UUID order, UUID store) {
        events.publishEvent(new OrderPacked(tenant, order, store));
    }
}
