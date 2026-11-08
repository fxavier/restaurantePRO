package com.xavier.backend.orders.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.delivery.DeliveryFixtures.DeliveryShop;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A merge that empties a DELIVERY order cancels it through the same path as the cancel command: a PENDING delivery is
 * cancelled with it; one OUT_FOR_DELIVERY refuses the whole merge (422 {@code DELIVERY_IN_PROGRESS}).
 */
@OrdersModuleTest
class DeliveryMergeTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

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
    void mergingAPendingDeliveryOrderAwayCancelsItsDelivery() throws Exception {
        UUID source = deliveries.confirmedDelivery(d, employee, terminal);
        UUID target = OrdersFixtures.id(fixtures.openedOrder(d.shop(), employee, terminal));

        merge(source, target).andExpect(status().isCreated());

        assertThat(deliveries.lifecycle(source)).isEqualTo("CANCELLED");
        assertThat(deliveries.deliveryStatus(source)).isEqualTo("CANCELLED");
        assertThat(fixtures.netQuantity(target)).isEqualByComparingTo("2");
    }

    @Test
    void mergingAnOutForDeliveryOrderAwayIsRefusedAndChangesNothing() throws Exception {
        UUID source = deliveries.confirmedDelivery(d, employee, terminal);
        deliveries.dispatch(d, source, 0, d.courier()).andExpect(status().isOk());
        UUID target = OrdersFixtures.id(fixtures.openedOrder(d.shop(), employee, terminal));

        merge(source, target).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DELIVERY_IN_PROGRESS"));

        assertThat(deliveries.lifecycle(source)).isEqualTo("OPEN");
        assertThat(deliveries.deliveryStatus(source)).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(fixtures.netQuantity(source)).isEqualByComparingTo("2");
    }

    org.springframework.test.web.servlet.ResultActions merge(UUID source, UUID target) throws Exception {
        Map<UUID, String> lines = new LinkedHashMap<>();
        jdbc.sql("SELECT id, quantity FROM orders.order_line WHERE order_id = ? AND status = 'CONFIRMED' ORDER BY id")
                .param(source).query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getBigDecimal(2)))
                .list().forEach(line -> lines.put(line.getKey(), line.getValue().toPlainString()));
        assertThat(lines.values().stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("2");
        return fixtures.transfer(d.shop(), source, deliveries.orderVersion(source), target,
                deliveries.orderVersion(target), lines, true, employee, terminal, UUID.randomUUID().toString());
    }
}
