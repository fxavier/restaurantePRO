package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.delivery.DeliveryFixtures;
import com.xavier.backend.orders.delivery.DeliveryFixtures.DeliveryShop;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D129, A51 (production half): a round of a DELIVERY order prints "ENTREGA #n" and one of a TAKEAWAY order
 * "TAKEAWAY #n", instead of the table/order header, and so does a void of a delivery's line. The BAR printer is on HOLD, so no worker touches its jobs.
 */
@ProductionModuleTest
class DeliveryHeaderTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    OrdersFixtures orders;

    @Autowired
    CustomersFixtures customers;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();

    @Test
    void aDeliveryRoundIsHeadedEntregaAndItsNumber() throws Exception {
        DeliveryFixtures deliveries = new DeliveryFixtures(mvc, jdbc, orders, customers);
        DeliveryShop d = deliveries.shop();
        heldBar(d.shop());

        UUID order = deliveries.confirmedDelivery(d, employee, terminal);

        UUID batch = PosRequests.lastBatch(jdbc, order);
        await(() -> fixtures.tickets(batch).size() == 1, "the round's BAR ticket");
        assertThat((String) fixtures.tickets(batch).getFirst().get("content"))
                .contains("ENTREGA #" + PosRequests.orderNumber(jdbc, order), "1 x Coca-Cola 33cl")
                .doesNotContain("Pedido #");
    }

    @Test
    void aVoidOnADeliveryIsHeadedEntregaAndItsNumber() throws Exception {
        DeliveryFixtures deliveries = new DeliveryFixtures(mvc, jdbc, orders, customers);
        DeliveryShop d = deliveries.shop();
        heldBar(d.shop());
        UUID order = deliveries.confirmedDelivery(d, employee, terminal);
        UUID line = jdbc.sql("SELECT id FROM orders.order_line WHERE order_id = ? AND item_id = ?").param(order)
                .param(d.shop().item()).query(UUID.class).single();

        UUID voidId = PosRequests.voided(mvc, jdbc, d.shop().store(), order, line, "1",
                orders.employee(d.shop(), IdentityFixtures.MANAGER));

        await(() -> fixtures.tickets(voidId).size() == 1, "the void's BAR ticket");
        assertThat((String) fixtures.tickets(voidId).getFirst().get("content")).contains(TicketLayouts.VOID_TITLE,
                "ENTREGA #" + PosRequests.orderNumber(jdbc, order), "-1 x Coca-Cola 33cl").doesNotContain("Pedido #");
    }

    @Test
    void aTakeawayRoundIsHeadedTakeawayAndItsNumber() throws Exception {
        Shop shop = fixtures.shop();
        heldBar(shop);
        UUID order = OrdersFixtures.id(PosRequests.openWalkIn(mvc, shop.store(), "TAKEAWAY", employee, terminal));
        UUID line = OrdersFixtures.id(orders.draft(shop, order, "2", employee, terminal));

        orders.confirm(shop, order, 0, shop.catalogVersion(), List.of(line), employee, terminal,
                UUID.randomUUID().toString()).andExpect(status().isCreated());

        UUID batch = PosRequests.lastBatch(jdbc, order);
        await(() -> fixtures.tickets(batch).size() == 1, "the round's BAR ticket");
        assertThat((String) fixtures.tickets(batch).getFirst().get("content"))
                .contains("TAKEAWAY #" + PosRequests.orderNumber(jdbc, order), "2 x Coca-Cola 33cl")
                .doesNotContain("Pedido #");
    }

    private void heldBar(Shop shop) throws Exception {
        UUID printer = fixtures.barPrinter(shop.store(), "127.0.0.1", 9);
        fixtures.mode(shop.store(), printer, "HOLD");
    }
}
