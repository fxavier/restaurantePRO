package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.model.event.Line;
import com.xavier.backend.orders.model.event.OrderLinesVoided;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionFixtures.Round;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D89 (1): work for a destination with no route, or a terminal with no printer, is kept as an UNROUTED ticket with
 * no job, counted and announced, so it can be routed later (spec §4 "falha de impressora não perde o pedido").
 */
@ProductionModuleTest
class UnroutedTicketTest {

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MeterRegistry meters;

    @Autowired
    ChangeNotifier notifier;

    final UUID employee = UUID.randomUUID();
    UUID terminal;
    Shop shop;

    @BeforeEach
    void shopWithoutRoutes() throws Exception {
        terminal = UUID.randomUUID();
        shop = fixtures.shop();
    }

    @Test
    void aRoundForADestinationWithNoRouteIsKeptAsAnUnroutedTicketWithNoJob() throws Exception {
        double unroutedBefore = unrouted();

        Round round = fixtures.confirmedRound(shop, employee, terminal, "1");

        await(() -> "DISPATCHED".equals(fixtures.batchStatus(round.batch())), "batch dispatched");
        assertThat(fixtures.tickets(round.batch())).singleElement().satisfies(ticket -> assertThat(ticket)
                .containsEntry("routing", "UNROUTED").containsEntry("printer_id", null)
                .containsEntry("suppressed", false).containsEntry("destination", ProductionFixtures.DESTINATION));
        assertThat(fixtures.jobsOfSource(round.batch())).isEmpty();
        assertThat(unrouted()).isGreaterThan(unroutedBefore);
        UUID ticket = (UUID) fixtures.tickets(round.batch()).getFirst().get("id");
        verify(notifier, timeout(5000)).notifyAfterCommit(eq(shop.store()), eq(ChangeTopic.PRINT_JOBS), eq(ticket));
    }

    @Test
    void aRedeliveredUnroutedEventStillMakesOneTicket() throws Exception {
        OrderLinesVoided voided = voidOn("BAR");

        publish(voided);
        await(() -> fixtures.tickets(voided.voidId()).size() == 1, "unrouted void ticket");
        double unroutedAfterFirst = unrouted();
        publish(voided);
        await(() -> completions(voided) == 2, "second delivery handled");

        assertThat(fixtures.tickets(voided.voidId())).singleElement()
                .satisfies(ticket -> assertThat(ticket).containsEntry("routing", "UNROUTED"));
        assertThat(unrouted()).isEqualTo(unroutedAfterFirst);
    }

    private OrderLinesVoided voidOn(String destination) {
        return new OrderLinesVoided(UUID.randomUUID(), UUID.randomUUID(), shop.store(), terminal, 77, "M1",
                new Line(UUID.randomUUID(), "I1", "Coca-Cola 33cl", null, new BigDecimal("3"), null, destination),
                BigDecimal.ONE, "cliente desistiu", Instant.parse("2026-11-04T12:00:00Z"));
    }

    private long completions(OrderLinesVoided voided) {
        return fixtures.completedPublications(voided.voidId());
    }

    private double unrouted() {
        return meters.get(ProductionMetrics.UNROUTED).counter().count();
    }

    private void publish(Object event) {
        tx.executeWithoutResult(status -> events.publishEvent(event));
    }
}
