package com.xavier.backend.orders.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.orders.BillingLock;
import com.xavier.backend.orders.DeliveryStatus;
import com.xavier.backend.orders.DeliverySummary;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.delivery.DeliveryFixtures.DeliveryShop;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * §8.3, D127: the order's lifecycle, its financial state (the billing lock) and the delivery status are independent.
 * No delivery transition moves the order's version, lifecycle or lock; a payment lock leaves the delivery as it was
 * and does not stop it; the billing view carries the delivery's summary for settlement (D129).
 */
@OrdersModuleTest
class DeliveryStatesIndependentTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    DeliveryFixtures deliveries;
    DeliveryShop d;

    @BeforeEach
    void aDeliveryShop() throws Exception {
        deliveries = new DeliveryFixtures(mvc, jdbc, fixtures, customers);
        d = deliveries.shop();
    }

    @Test
    void theBillingViewCarriesTheDeliverySummaryAndOnlyForADeliveryOrder() throws Exception {
        UUID order = deliveries.confirmedDelivery(d, employee, terminal);
        UUID table = OrdersFixtures.id(fixtures.openedOrder(d.shop(), employee, terminal));
        String zoneCode = jdbc.sql("SELECT code FROM orders.delivery_zone WHERE id = ?").param(d.zone())
                .query(String.class).single();

        assertThat(billing(order).delivery()).isEqualTo(new DeliverySummary(DeliveryStatus.PENDING, null, null,
                zoneCode));
        assertThat(billing(table).delivery()).isNull();

        deliveries.dispatch(d, order, 0, d.courier()).andExpect(status().isOk());

        assertThat(billing(order).delivery()).isEqualTo(new DeliverySummary(DeliveryStatus.OUT_FOR_DELIVERY,
                d.courier(), null, zoneCode));
        assertThat(inPos(() -> ordersApi.openBillingViews(d.shop().store())).stream()
                .filter(view -> view.orderId().equals(order)).findFirst().orElseThrow().delivery().status())
                .isEqualTo(DeliveryStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void deliveryTransitionsNeverTouchTheOrderAndAPaymentLockNeverTouchesTheDelivery() throws Exception {
        UUID order = deliveries.confirmedDelivery(d, employee, terminal);
        OrderBillingView before = billing(order);

        inPos(() -> {
            ordersApi.lockForPayment(order);
            return null;
        });
        assertThat(deliveries.deliveryStatus(order)).isEqualTo("PENDING");
        assertThat(deliveries.version(order)).isZero();
        long locked = deliveries.orderVersion(order);
        assertThat(locked).isEqualTo(before.version() + 1);

        deliveries.dispatch(d, order, 0, d.courier()).andExpect(status().isOk());
        deliveries.returned(d, order, 1).andExpect(status().isOk());
        deliveries.dispatch(d, order, 2, d.courier()).andExpect(status().isOk());
        deliveries.delivered(d, order, 3).andExpect(status().isOk());

        OrderBillingView after = billing(order);
        assertThat(after.version()).isEqualTo(locked);
        assertThat(after.lifecycle()).isEqualTo(OrderLifecycle.OPEN);
        assertThat(after.billingLock()).isEqualTo(BillingLock.PAYMENT);
        assertThat(after.due()).isEqualTo(before.due());
        assertThat(after.delivery().status()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    OrderBillingView billing(UUID order) {
        return inPos(() -> ordersApi.billingView(order));
    }

    <T> T inPos(Supplier<T> action) {
        return OrdersActors.runAsPos(d.shop().store(), employee, terminal,
                () -> new TransactionTemplate(transactionManager).execute(status -> action.get()));
    }
}
