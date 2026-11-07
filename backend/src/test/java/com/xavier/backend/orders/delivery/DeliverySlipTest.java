package com.xavier.backend.orders.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.orders.DeliverySlip;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersProductionApi;
import com.xavier.backend.orders.delivery.DeliveryFixtures.DeliveryShop;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.exception.NotFoundException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** D129: the courier's slip, as production's listener reads it (no session). */
@OrdersModuleTest
class DeliverySlipTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    OrdersProductionApi productionApi;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void theSlipNamesTheCustomerAddressZoneLinesAndDue() throws Exception {
        DeliveryFixtures deliveries = new DeliveryFixtures(mvc, jdbc, fixtures, customers);
        DeliveryShop d = deliveries.shop();
        UUID order = deliveries.confirmedDelivery(d, UUID.randomUUID(), UUID.randomUUID());
        UUID table = OrdersFixtures.id(fixtures.openedOrder(d.shop(), UUID.randomUUID(), UUID.randomUUID()));
        long number = jdbc.sql("SELECT order_number FROM orders.order_header WHERE id = ?").param(order)
                .query(Long.class).single();
        String name = jdbc.sql("SELECT name FROM customers.customer WHERE id = ?").param(d.customer())
                .query(String.class).single();
        String zone = jdbc.sql("SELECT code FROM orders.delivery_zone WHERE id = ?").param(d.zone())
                .query(String.class).single();
        SecurityContextHolder.clearContext();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        DeliverySlip slip = tx.execute(status -> productionApi.deliverySlip(order));

        assertThat(slip.orderNumber()).isEqualTo(number);
        assertThat(slip.customerName()).isEqualTo(name);
        assertThat(slip.phone()).isEqualTo(d.phone());
        assertThat(slip.address()).isEqualTo(new DeliverySlip.Address("Rua da Entrega 7", null, "Maputo", null,
                "portão azul"));
        assertThat(slip.zone()).isEqualTo(zone);
        assertThat(slip.promisedAt()).isNull();
        assertThat(slip.lines()).extracting(DeliverySlip.Line::description)
                .containsExactlyInAnyOrder("Taxa de entrega", "Coca-Cola 33cl");
        assertThat(slip.lines()).extracting(DeliverySlip.Line::quantity)
                .allSatisfy(quantity -> assertThat(quantity).isEqualByComparingTo(BigDecimal.ONE));
        assertThat(slip.due()).isEqualTo(Money.of("15.50", java.util.Currency.getInstance("MZN")));
        assertThatThrownBy(() -> tx.execute(status -> productionApi.deliverySlip(table)))
                .isInstanceOf(NotFoundException.class);
    }
}
