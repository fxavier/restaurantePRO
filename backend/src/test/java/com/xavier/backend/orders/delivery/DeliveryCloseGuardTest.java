package com.xavier.backend.orders.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.delivery.DeliveryFixtures.DeliveryShop;
import com.xavier.backend.orders.delivery.exception.DeliveryRuleException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D127: settlement's close refuses a DELIVERY order until it is DELIVERED (422 {@code DELIVERY_NOT_DELIVERED}); the
 * order's cancel takes its delivery to CANCELLED from PENDING or RETURNED, and is refused while it is out or delivered
 * (422 {@code DELIVERY_IN_PROGRESS}), leaving the order OPEN.
 */
@OrdersModuleTest
class DeliveryCloseGuardTest {

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
    void theCloseIsRefusedUntilTheDeliveryIsDelivered() throws Exception {
        UUID order = deliveries.confirmedDelivery(d, employee, terminal);
        assertNotClosable(order); // PENDING
        deliveries.dispatch(d, order, 0, d.courier()).andExpect(status().isOk());
        assertNotClosable(order); // OUT_FOR_DELIVERY
        deliveries.returned(d, order, 1).andExpect(status().isOk());
        assertNotClosable(order); // RETURNED
        deliveries.dispatch(d, order, 2, d.courier()).andExpect(status().isOk());
        deliveries.delivered(d, order, 3).andExpect(status().isOk());

        close(order);

        assertThat(deliveries.lifecycle(order)).isEqualTo("CLOSED");
        assertThat(deliveries.deliveryStatus(order)).isEqualTo("DELIVERED");
    }

    @Test
    void cancellingAPendingDeliveryOrderCancelsItsDelivery() throws Exception {
        UUID order = OrdersFixtures.id(deliveries.opened(d, employee, terminal));

        deliveries.cancel(d, order, 0).andExpect(status().isOk());

        assertThat(deliveries.lifecycle(order)).isEqualTo("CANCELLED");
        assertThat(deliveries.deliveryStatus(order)).isEqualTo("CANCELLED");
        assertThat(deliveries.history(order)).containsExactly("->PENDING", "PENDING>CANCELLED");
    }

    @Test
    void aReturnedDeliveryIsCancelledWithItsVoidedOrderButAnOutOneIsNot() throws Exception {
        UUID order = deliveries.confirmedDelivery(d, employee, terminal);
        deliveries.dispatch(d, order, 0, d.courier()).andExpect(status().isOk());
        deliveries.voidAll(order);

        deliveries.cancel(d, order, 1).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DELIVERY_IN_PROGRESS"));
        assertThat(deliveries.lifecycle(order)).isEqualTo("OPEN");
        assertThat(deliveries.deliveryStatus(order)).isEqualTo("OUT_FOR_DELIVERY");

        deliveries.returned(d, order, 1).andExpect(status().isOk());
        deliveries.cancel(d, order, 1).andExpect(status().isOk());

        assertThat(deliveries.lifecycle(order)).isEqualTo("CANCELLED");
        assertThat(deliveries.history(order)).containsExactly("->PENDING", "PENDING>OUT_FOR_DELIVERY",
                "OUT_FOR_DELIVERY>RETURNED", "RETURNED>CANCELLED");
    }

    void assertNotClosable(UUID order) {
        assertThatThrownBy(() -> close(order)).isInstanceOf(DeliveryRuleException.class)
                .hasFieldOrPropertyWithValue("code", "DELIVERY_NOT_DELIVERED");
        assertThat(deliveries.lifecycle(order)).isEqualTo("OPEN");
    }

    void close(UUID order) {
        long version = deliveries.orderVersion(order);
        OrdersActors.runAsPos(d.shop().store(), employee, terminal,
                () -> new TransactionTemplate(transactionManager).execute(status -> {
                    ordersApi.close(order, version, employee);
                    return null;
                }));
    }
}
