package com.xavier.archfixture.tenant.orders.service;

import com.xavier.archfixture.tenant.orders.model.event.OrderPacked;
import com.xavier.archfixture.tenant.orders.model.event.OrderShipped;
import org.springframework.modulith.events.ApplicationModuleListener;

/** Fixture: listens to an event without a tenant, and to one with it. */
public class ShippedListener {

    @ApplicationModuleListener
    void on(OrderShipped event) {
        // fixture
    }

    @ApplicationModuleListener
    void on(OrderPacked event) {
        // fixture
    }
}
