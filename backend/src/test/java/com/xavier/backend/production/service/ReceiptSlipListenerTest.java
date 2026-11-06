package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.settlement.model.event.ReceiptSlipRequested;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D75: a debt payment's non-fiscal slip prints once on the paying terminal's printer, as a RECEIPT_SLIP ticket. A
 * redelivered event prints nothing more; a terminal with no printer keeps the slip as an UNROUTED ticket (D89 (1)).
 * The printer is on HOLD, so no worker changes the jobs counted here.
 */
@ProductionModuleTest
class ReceiptSlipListenerTest {

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    Shop shop;
    UUID terminal;

    @BeforeEach
    void heldTerminalPrinter() throws Exception {
        shop = fixtures.shop();
        terminal = UUID.randomUUID();
        UUID counter = fixtures.printer(shop.store(), "RS" + UUID.randomUUID().toString().substring(0, 6),
                "127.0.0.1", 9);
        fixtures.mode(shop.store(), counter, "HOLD");
        fixtures.terminalPrinters(shop.store(), Map.of(terminal, counter)).andExpect(status().isOk());
    }

    @Test
    void aRedeliveredSlipPrintsOnceOnTheTerminalsPrinter() throws Exception {
        UUID payment = UUID.randomUUID();
        publish(slip(payment, terminal));
        await(() -> completed(payment) == 1, "first delivery handled");

        publish(slip(payment, terminal));
        await(() -> completed(payment) == 2, "redelivery handled");

        assertThat(fixtures.tickets(payment)).singleElement().satisfies(ticket -> assertThat(ticket)
                .containsEntry("source_type", "RECEIPT_SLIP").containsEntry("routing", "ROUTED"));
        assertThat(fixtures.jobsOfSource(payment)).hasSize(1);
    }

    @Test
    void aTerminalWithoutPrinterKeepsTheSlipUnrouted() throws Exception {
        UUID payment = UUID.randomUUID();
        publish(slip(payment, UUID.randomUUID()));
        await(() -> completed(payment) == 1, "delivery handled");

        assertThat(fixtures.tickets(payment)).singleElement().satisfies(ticket -> assertThat(ticket)
                .containsEntry("source_type", "RECEIPT_SLIP").containsEntry("routing", "UNROUTED"));
        assertThat(fixtures.jobsOfSource(payment)).isEmpty();
    }

    private ReceiptSlipRequested slip(UUID payment, UUID onTerminal) {
        return new ReceiptSlipRequested(payment, shop.store(), onTerminal,
                List.of("PAGAMENTO DE CONTA CORRENTE", "Valor pago: 30.00 MZN"));
    }

    /** Deliveries of this payment's slip event that the receipt slip listener completed. */
    private long completed(UUID payment) {
        return jdbc.sql("""
                        SELECT count(*) FROM event_publication WHERE serialized_event LIKE :id
                          AND listener_id LIKE '%ReceiptSlipListener%' AND completion_date IS NOT NULL""")
                .param("id", "%" + payment + "%").query(Long.class).single();
    }

    private void publish(Object event) {
        tx.executeWithoutResult(status -> events.publishEvent(event));
    }
}
