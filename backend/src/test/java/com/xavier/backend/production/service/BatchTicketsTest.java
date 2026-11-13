package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.model.event.Line;
import com.xavier.backend.orders.model.event.OrderBatchConfirmed;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionFixtures.Round;
import com.xavier.backend.production.ProductionModuleTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A4, production half (D50 §7.1): a confirmed round becomes one ticket per destination with a QUEUED job, holding
 * only that round's lines, and the batch becomes DISPATCHED. A redelivered event creates nothing more. Work with no
 * route is kept UNROUTED, with no job, and is counted (D89 (1)). The BAR printer is on HOLD: full-application
 * contexts cached in the same JVM run the print worker against this database, and a held job stays QUEUED, so its
 * state is the listener's alone.
 */
@ProductionModuleTest
class BatchTicketsTest {

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    TicketService tickets;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MeterRegistry meters;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;

    @BeforeEach
    void shop() throws Exception {
        shop = fixtures.shop();
    }

    @Test
    void aRoundBecomesOneTicketPerDestinationWithAQueuedJobAndIsDispatched() throws Exception {
        UUID printer = heldBarPrinter();

        Round round = fixtures.confirmedRound(shop, employee, terminal, "2");

        await(() -> "DISPATCHED".equals(fixtures.batchStatus(round.batch())), "batch dispatched");
        List<Map<String, Object>> tickets = fixtures.tickets(round.batch());
        assertThat(tickets).singleElement().satisfies(ticket -> {
            assertThat(ticket).containsEntry("source_type", "BATCH").containsEntry("destination", "BAR")
                    .containsEntry("printer_id", printer).containsEntry("suppressed", false)
                    .containsEntry("order_id", round.order());
            assertThat(ProductionFixtures.text(ticket)).contains("BAR", "Mesa M1 - Pedido #" + round.orderNumber(),
                    "Ronda 1", "2 x Coca-Cola 33cl");
        });
        assertThat(fixtures.jobsOfSource(round.batch())).singleElement().satisfies(job -> assertThat(job)
                .containsEntry("status", "QUEUED").containsEntry("printer_id", printer)
                .containsEntry("kind", "ORIGINAL").containsEntry("attempts", 0));
    }

    @Test
    void aSecondRoundPrintsOnlyItsNewLines() throws Exception {
        heldBarPrinter();
        Round first = fixtures.confirmedRound(shop, employee, terminal, "2");

        Round second = fixtures.nextRound(shop, first.order(), first.orderNumber(), first.orderVersion(), employee,
                terminal, "3");

        await(() -> fixtures.tickets(second.batch()).size() == 1, "second round ticket");
        String content = ProductionFixtures.text(fixtures.tickets(second.batch()).getFirst());
        assertThat(content).contains("Ronda 2", "3 x Coca-Cola 33cl").doesNotContain("2 x Coca-Cola 33cl");
        assertThat(fixtures.tickets(first.batch())).hasSize(1);
    }

    @Test
    void aRedeliveredRoundCreatesNoSecondTicketOrJob() throws Exception {
        heldBarPrinter();
        Round round = fixtures.confirmedRound(shop, employee, terminal, "1");
        await(() -> "DISPATCHED".equals(fixtures.batchStatus(round.batch())), "batch dispatched");

        OrderBatchConfirmed again = event(round, List.of(new Line(UUID.randomUUID(), "I", "Coca-Cola 33cl", null,
                java.math.BigDecimal.ONE, null, "BAR")));
        tx.executeWithoutResult(status -> tickets.receiveBatch(again));
        tx.executeWithoutResult(status -> tickets.receiveBatch(again));

        assertThat(fixtures.tickets(round.batch())).hasSize(1);
        assertThat(fixtures.jobsOfSource(round.batch())).hasSize(1);
        assertThat(fixtures.batchStatus(round.batch())).isEqualTo("DISPATCHED");
    }

    @Test
    void aDestinationWithoutRouteIsKeptUnroutedAndCountedAndTheRoundIsStillDispatched() throws Exception {
        double unroutedBefore = meters.get(ProductionMetrics.UNROUTED).counter().count();

        Round round = fixtures.confirmedRound(shop, employee, terminal, "1");

        await(() -> "DISPATCHED".equals(fixtures.batchStatus(round.batch())), "batch dispatched");
        assertThat(fixtures.tickets(round.batch())).singleElement()
                .satisfies(ticket -> assertThat(ticket).containsEntry("routing", "UNROUTED"));
        assertThat(fixtures.jobsOfSource(round.batch())).isEmpty();
        assertThat(meters.get(ProductionMetrics.UNROUTED).counter().count()).isGreaterThan(unroutedBefore);
    }

    @Test
    void linesWithoutDestinationAreLeftOut() throws Exception {
        heldBarPrinter();
        Round round = fixtures.confirmedRound(shop, employee, terminal, "1");
        await(() -> "DISPATCHED".equals(fixtures.batchStatus(round.batch())), "batch dispatched");
        UUID otherBatch = round.batch();

        // the same batch redelivered with an extra line that nothing prepares: still the one BAR ticket
        tx.executeWithoutResult(status -> tickets.receiveBatch(event(round, List.of(
                new Line(UUID.randomUUID(), "I", "Coca-Cola 33cl", null, java.math.BigDecimal.ONE, null, "BAR"),
                new Line(UUID.randomUUID(), "W", "Água", null, java.math.BigDecimal.ONE, null, null)))));

        assertThat(fixtures.tickets(otherBatch)).extracting(t -> t.get("destination")).containsExactly("BAR");
    }

    /** Routed and on HOLD: no worker of any context takes its jobs. */
    private UUID heldBarPrinter() throws Exception {
        UUID printer = fixtures.barPrinter(shop.store(), "127.0.0.1", 9);
        fixtures.mode(shop.store(), printer, "HOLD");
        return printer;
    }

    private OrderBatchConfirmed event(Round round, List<Line> lines) {
        return new OrderBatchConfirmed(round.order(), round.batch(), 1, shop.store(), terminal, round.orderNumber(),
                "M1", employee, Instant.parse("2026-11-04T12:00:00Z"), lines);
    }
}
