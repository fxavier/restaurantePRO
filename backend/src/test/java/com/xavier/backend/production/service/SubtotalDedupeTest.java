package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.model.event.SubtotalLine;
import com.xavier.backend.orders.model.event.SubtotalRequested;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.Currency;
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
 * D89 (2): the SUBTOTAL ticket dedupes on the subtotal's request id, so a deliberate second subtotal of an unchanged
 * bill prints and a redelivered event does not. A publication stored by R2 (no request id) falls back to the R2
 * content digest. The terminal's printer is on HOLD, so no worker changes the jobs these tests count.
 */
@ProductionModuleTest
class SubtotalDedupeTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    UUID terminal;
    Shop shop;
    UUID order;

    @BeforeEach
    void heldCounterPrinter() throws Exception {
        terminal = UUID.randomUUID();
        order = UUID.randomUUID();
        shop = fixtures.shop();
        UUID counter = fixtures.printer(shop.store(), "CX" + UUID.randomUUID().toString().substring(0, 6),
                "127.0.0.1", 9);
        fixtures.mode(shop.store(), counter, "HOLD");
        fixtures.terminalPrinters(shop.store(), Map.of(terminal, counter)).andExpect(status().isOk());
    }

    @Test
    void twoSubtotalRequestsOfAnUnchangedBillPrintTwice() throws Exception {
        publish(subtotal(UUID.randomUUID()));
        publish(subtotal(UUID.randomUUID()));

        await(() -> subtotalTicketsOfOrder() == 2, "two subtotal tickets");
        assertThat(jobsOfOrder()).isEqualTo(2);
    }

    @Test
    void aRedeliveredSubtotalRequestPrintsOnce() throws Exception {
        UUID request = UUID.randomUUID();
        publish(subtotal(request));
        await(() -> fixtures.completedPublications(request) == 1, "first delivery handled");

        publish(subtotal(request));
        await(() -> fixtures.completedPublications(request) == 2, "redelivery handled");

        assertThat(subtotalTicketsOfOrder()).isEqualTo(1);
        assertThat(jobsOfOrder()).isEqualTo(1);
    }

    @Test
    void anR2PublicationWithoutRequestIdFallsBackToTheContentDigest() throws Exception {
        publish(subtotal(null));
        await(() -> fixtures.completedPublications(order) == 1, "first delivery handled");

        publish(subtotal(null));
        await(() -> fixtures.completedPublications(order) == 2, "redelivery handled");

        assertThat(subtotalTicketsOfOrder()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT destination FROM production.production_ticket
                        WHERE order_id = ? AND source_type = 'SUBTOTAL'""").param(order).query(String.class).single())
                .startsWith("SUBTOTAL-");
    }

    private SubtotalRequested subtotal(UUID requestId) {
        return new SubtotalRequested(order, shop.store(), terminal, 81, "M1",
                List.of(new SubtotalLine("Coca-Cola 33cl", new BigDecimal("2"), Money.of("12.50", MZN),
                        Money.of("25.00", MZN))), Money.of("25.00", MZN), requestId);
    }

    private long subtotalTicketsOfOrder() {
        return jdbc.sql("""
                        SELECT count(*) FROM production.production_ticket
                        WHERE order_id = ? AND source_type = 'SUBTOTAL'""").param(order).query(Long.class).single();
    }

    private long jobsOfOrder() {
        return jdbc.sql("""
                        SELECT count(*) FROM production.print_job j JOIN production.production_ticket t
                          ON t.id = j.ticket_id WHERE t.order_id = ?""").param(order).query(Long.class).single();
    }

    private void publish(Object event) {
        tx.executeWithoutResult(status -> events.publishEvent(event));
    }
}
