package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.delivery.DeliveryFixtures;
import com.xavier.backend.orders.delivery.DeliveryFixtures.DeliveryShop;
import com.xavier.backend.orders.model.event.DeliveryDispatched;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D129, A51 (slip): a dispatch prints a non-fiscal DELIVERY_SLIP on the dispatching terminal's printer, deduped on
 * the dispatch's delivery event: a redelivered event prints nothing more, a re-dispatch after a return prints again,
 * and a terminal with no printer keeps the slip UNROUTED (D89 (1)). The terminal's printer is on HOLD, so no worker
 * touches the jobs counted here.
 */
@ProductionModuleTest
class DeliverySlipOnceTest {

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

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    UUID terminal;
    DeliveryFixtures deliveries;
    DeliveryShop d;
    UUID printer;
    UUID order;

    @BeforeEach
    void aConfirmedDeliveryAndAHeldTerminalPrinter() throws Exception {
        terminal = UUID.randomUUID();
        deliveries = new DeliveryFixtures(mvc, jdbc, orders, customers);
        d = deliveries.shop();
        printer = fixtures.printer(d.shop().store(), "DS" + UUID.randomUUID().toString().substring(0, 6),
                "127.0.0.1", 9);
        fixtures.mode(d.shop().store(), printer, "HOLD");
        fixtures.terminalPrinters(d.shop().store(), Map.of(terminal, printer)).andExpect(status().isOk());
        order = deliveries.confirmedDelivery(d, UUID.randomUUID(), terminal);
    }

    @Test
    void aRedeliveredDispatchPrintsOneSlipOnTheDispatchingTerminalsPrinter() throws Exception {
        UUID dispatch = dispatchFrom(terminal);
        await(() -> completed(dispatch) == 1, "first delivery handled");

        publish(new DeliveryDispatched(order, dispatch, d.shop().store(), terminal));
        await(() -> completed(dispatch) == 2, "redelivery handled");

        assertThat(fixtures.tickets(dispatch)).singleElement().satisfies(ticket -> {
            assertThat(ticket).containsEntry("source_type", "DELIVERY_SLIP").containsEntry("routing", "ROUTED")
                    .containsEntry("printer_id", printer).containsEntry("order_id", order)
                    .containsEntry("terminal_id", terminal);
            assertThat(ProductionFixtures.text(ticket)).contains(TicketLayouts.DELIVERY_SLIP_TITLE,
                    "ENTREGA #" + PosRequests.orderNumber(jdbc, order), "Tel: " + d.phone(), "Rua da Entrega 7",
                    "Notas: portão azul", "1 x Taxa de entrega", "1 x Coca-Cola 33cl", "A PAGAR 15.50 MZN");
        });
        assertThat(fixtures.jobsOfSource(dispatch)).singleElement()
                .satisfies(job -> assertThat(job).containsEntry("printer_id", printer));
    }

    @Test
    void aReDispatchAfterAReturnPrintsASecondSlip() throws Exception {
        UUID first = dispatchFrom(terminal);
        await(() -> completed(first) == 1, "first dispatch handled");
        PosRequests.delivery(mvc, d, order, terminal, "returned", """
                {"version": %d, "reason": "nobody home"}""".formatted(deliveries.version(order)))
                .andExpect(status().isOk());

        UUID second = dispatchFrom(terminal);
        await(() -> completed(second) == 1, "re-dispatch handled");

        assertThat(second).isNotEqualTo(first);
        assertThat(slipsOfOrder()).containsExactlyInAnyOrder(first, second);
        assertThat(fixtures.jobsOfSource(second)).hasSize(1);
    }

    @Test
    void aTerminalWithoutPrinterKeepsTheSlipUnrouted() throws Exception {
        UUID dispatch = dispatchFrom(UUID.randomUUID());
        await(() -> completed(dispatch) == 1, "dispatch handled");

        assertThat(fixtures.tickets(dispatch)).singleElement().satisfies(ticket -> assertThat(ticket)
                .containsEntry("source_type", "DELIVERY_SLIP").containsEntry("routing", "UNROUTED"));
        assertThat(fixtures.jobsOfSource(dispatch)).isEmpty();
    }

    /** Dispatches the order from {@code from}'s POS; the id of the delivery event it wrote. */
    private UUID dispatchFrom(UUID from) throws Exception {
        PosRequests.delivery(mvc, d, order, from, "dispatch", """
                {"version": %d, "courierEmployeeId": "%s"}""".formatted(deliveries.version(order), d.courier()))
                .andExpect(status().isOk());
        return jdbc.sql("""
                        SELECT id FROM orders.delivery_event WHERE order_id = ? AND to_status = 'OUT_FOR_DELIVERY'
                        ORDER BY id DESC LIMIT 1""").param(order).query(UUID.class).single();
    }

    private List<UUID> slipsOfOrder() {
        return jdbc.sql("""
                        SELECT source_id FROM production.production_ticket
                        WHERE order_id = ? AND source_type = 'DELIVERY_SLIP'""").param(order).query(UUID.class)
                .list();
    }

    /** Deliveries of this dispatch's event that the slip listener completed. */
    private long completed(UUID dispatch) {
        return jdbc.sql("""
                        SELECT count(*) FROM event_publication WHERE serialized_event LIKE :id
                          AND listener_id LIKE '%DeliverySlipListener%' AND completion_date IS NOT NULL""")
                .param("id", "%" + dispatch + "%").query(Long.class).single();
    }

    private void publish(Object event) {
        tx.executeWithoutResult(status -> events.publishEvent(event));
    }
}
