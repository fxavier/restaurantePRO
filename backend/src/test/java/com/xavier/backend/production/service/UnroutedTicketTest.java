package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static com.xavier.backend.fiscal.FiscalActors.inStore;
import static com.xavier.backend.fiscal.FiscalActors.pos;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.model.event.SubtotalRequested;
import com.xavier.backend.shared.Money;
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
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D89 (1): work for a destination with no route, or a terminal with no printer, is kept as an UNROUTED ticket with
 * no job, counted and announced, so it can be routed later (spec §4 "falha de impressora não perde o pedido").
 */
@ProductionModuleTest
class UnroutedTicketTest {

    static final String POS_TICKETS = "/api/v1/pos/production-tickets";
    static final String BACKOFFICE_TICKETS = "/api/v1/backoffice/production-tickets";
    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

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

    @Test
    void unroutedTicketsAreListedForTheStoreWithThePermissionToView() throws Exception {
        UUID ticket = unroutedVoidTicket();
        UUID otherStore = fixtures.shop().store();

        mvc.perform(get(BACKOFFICE_TICKETS).param("storeId", shop.store().toString()).param("routing", "UNROUTED")
                        .with(inStore(shop.store(), Permission.PRODUCTION_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ticket.toString()))
                .andExpect(jsonPath("$.content[0].routing").value("UNROUTED"))
                .andExpect(jsonPath("$.content[0].sourceType").value("VOID"))
                .andExpect(jsonPath("$.content[0].printerId").doesNotExist());
        mvc.perform(get(POS_TICKETS).param("routing", "UNROUTED")
                        .with(pos(shop.store(), employee, terminal, Permission.PRODUCTION_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(ticket.toString()));
        mvc.perform(get(POS_TICKETS).param("routing", "ROUTED")
                        .with(pos(shop.store(), employee, terminal, Permission.PRODUCTION_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(get(POS_TICKETS).param("routing", "UNROUTED")
                        .with(pos(otherStore, employee, terminal, Permission.PRODUCTION_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(get(BACKOFFICE_TICKETS).param("storeId", shop.store().toString())
                .with(inStore(otherStore, Permission.PRODUCTION_VIEW))).andExpect(status().isForbidden());
        mvc.perform(get(POS_TICKETS).with(pos(shop.store(), employee, terminal, Permission.PRODUCTION_OPERATE)))
                .andExpect(status().isForbidden());
    }

    /** A30: the route is added later, the ticket is routed once, and it gets exactly one job. */
    @Test
    void aRouteAddedLaterRoutesTheTicketOnceWithOneAuditedJob() throws Exception {
        UUID ticket = unroutedVoidTicket();
        UUID bar = heldBarPrinter();
        String key = key();

        String routed = route(ticket, key, operator()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketId").value(ticket.toString()))
                .andExpect(jsonPath("$.printerId").value(bar.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED")).andReturn().getResponse().getContentAsString();

        assertThat(route(ticket, key, operator()).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString()).isEqualTo(routed);
        route(ticket, key(), operator()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_ALREADY_ROUTED"));
        assertThat(ticketRow(ticket)).containsEntry("routing", "ROUTED").containsEntry("printer_id", bar);
        assertThat(jdbc.sql("SELECT dedupe_key FROM production.print_job WHERE ticket_id = ?").param(ticket)
                .query(String.class).list()).containsExactly("ticket:" + ticket);
        assertThat(fixtures.audits("PRODUCTION_TICKET_ROUTED", ticket)).isEqualTo(1);
    }

    @Test
    void routingWhileThereIsStillNoRouteIsRefusedAndTheTicketStaysUnrouted() throws Exception {
        UUID ticket = unroutedVoidTicket();

        route(ticket, key(), operator()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STILL_UNROUTED"));

        assertThat(ticketRow(ticket)).containsEntry("routing", "UNROUTED");
        assertThat(jdbc.sql("SELECT count(*) FROM production.print_job WHERE ticket_id = ?").param(ticket)
                .query(Long.class).single()).isZero();
    }

    /** A terminal-printed ticket routes to the terminal's printer, assigned later (here from BackOffice). */
    @Test
    void anUnroutedSubtotalRoutesToThePrinterTheTerminalGotLater() throws Exception {
        UUID request = UUID.randomUUID();
        publish(new SubtotalRequested(UUID.randomUUID(), shop.store(), terminal, 80, null, List.of(),
                Money.of("0.00", MZN), request));
        await(() -> fixtures.tickets(request).size() == 1, "unrouted subtotal");
        UUID ticket = (UUID) fixtures.tickets(request).getFirst().get("id");
        UUID counter = heldPrinter("CX");
        fixtures.terminalPrinters(shop.store(), Map.of(terminal, counter)).andExpect(status().isOk());

        mvc.perform(post(BACKOFFICE_TICKETS + "/" + ticket + "/route").header("Idempotency-Key", key())
                        .with(csrf()).with(inStore(shop.store(), Permission.PRODUCTION_OPERATE)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.printerId").value(counter.toString()));

        assertThat(ticketRow(ticket)).containsEntry("routing", "ROUTED").containsEntry("printer_id", counter);
    }

    @Test
    void aRouteToAnIgnoringPrinterIsRefusedAndTheTicketStaysUnrouted() throws Exception {
        UUID ticket = unroutedVoidTicket();
        UUID bar = heldBarPrinter();
        fixtures.mode(shop.store(), bar, "IGNORE");

        route(ticket, key(), operator()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRINTER_IGNORED"));

        assertThat(ticketRow(ticket)).containsEntry("routing", "UNROUTED");
    }

    @Test
    void routingIsScopedToTheTicketsStoreAndNeedsTheOperatePermission() throws Exception {
        UUID ticket = unroutedVoidTicket();
        heldBarPrinter();
        UUID otherStore = fixtures.shop().store();

        route(ticket, key(), pos(otherStore, employee, terminal, Permission.PRODUCTION_OPERATE))
                .andExpect(status().isForbidden());
        route(ticket, key(), pos(shop.store(), employee, terminal, Permission.PRODUCTION_VIEW))
                .andExpect(status().isForbidden());
        mvc.perform(post(BACKOFFICE_TICKETS + "/" + ticket + "/route").header("Idempotency-Key", key())
                .with(csrf()).with(inStore(otherStore, Permission.PRODUCTION_OPERATE)))
                .andExpect(status().isForbidden());
        route(UUID.randomUUID(), key(), operator()).andExpect(status().isNotFound());
        mvc.perform(post(POS_TICKETS + "/" + ticket + "/route").with(csrf()).with(operator()))
                .andExpect(status().isBadRequest());

        assertThat(ticketRow(ticket)).containsEntry("routing", "UNROUTED");
    }

    /** The table holds the rule too: routing is the one change, only UNROUTED -> ROUTED and only with a printer. */
    @Test
    void theTableAllowsOnlyRoutingAnUnroutedTicketToAPrinter() throws Exception {
        UUID ticket = unroutedVoidTicket();
        UUID bar = heldBarPrinter();

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE production.production_ticket SET routing = 'ROUTED', printer_id = ?, content = 'edited'
                WHERE id = ?""").param(bar).param(ticket).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("UPDATE production.production_ticket SET routing = 'ROUTED' WHERE id = ?")
                .param(ticket).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE production.production_ticket SET suppressed = true WHERE id = ?")
                .param(ticket).update()).hasMessageContaining("append-only");

        route(ticket, key(), operator()).andExpect(status().isCreated());
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE production.production_ticket SET routing = 'UNROUTED', printer_id = NULL WHERE id = ?""")
                .param(ticket).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM production.production_ticket WHERE id = ?").param(ticket)
                .update()).hasMessageContaining("append-only");
    }

    /** D75: the debt payment slip's source exists in the schema now, so S29 adds only its listener. */
    @Test
    void theTableAcceptsAReceiptSlipTicket() {
        UUID slip = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO production.production_ticket (id, store_id, source_type, source_id, destination, kind,
                    terminal_id, routing, suppressed, content, created_at)
                VALUES (?, ?, 'RECEIPT_SLIP', ?, 'ORIGINAL', 'ORIGINAL', ?, 'UNROUTED', false, 'slip', now())""")
                .param(UUID.randomUUID()).param(shop.store()).param(slip).param(terminal).update();

        assertThat(fixtures.tickets(slip)).singleElement()
                .satisfies(ticket -> assertThat(ticket).containsEntry("source_type", "RECEIPT_SLIP"));
    }

    /** There is no print to copy yet: an UNROUTED ticket is routed, not reprinted. */
    @Test
    void anUnroutedTicketIsNotReprinted() throws Exception {
        UUID ticket = unroutedVoidTicket();

        mvc.perform(post(POS_TICKETS + "/" + ticket + "/reprints").header("Idempotency-Key", key()).with(csrf())
                        .with(operator())).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STILL_UNROUTED"));
    }

    /** The compare-and-set: two operators routing the same ticket at once make one job, and one of them gets 409. */
    @Test
    void concurrentRoutesOfOneTicketMakeOneJob() throws Exception {
        UUID ticket = unroutedVoidTicket();
        heldBarPrinter();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> statuses = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                statuses.add(pool.submit(() -> {
                    start.await();
                    return route(ticket, key(), operator()).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> status : statuses) {
                results.add(status.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM production.print_job WHERE ticket_id = ?").param(ticket)
                .query(Long.class).single()).isEqualTo(1);
    }

    private UUID unroutedVoidTicket() throws InterruptedException {
        OrderLinesVoided voided = voidOn(ProductionFixtures.DESTINATION);
        publish(voided);
        await(() -> fixtures.tickets(voided.voidId()).size() == 1, "unrouted void ticket");
        return (UUID) fixtures.tickets(voided.voidId()).getFirst().get("id");
    }

    /** The shop's BAR destination routed to a printer on HOLD, so no worker sends what these tests count. */
    private UUID heldBarPrinter() throws Exception {
        UUID bar = heldPrinter("BAR");
        fixtures.routes(shop.store(), Map.of(ProductionFixtures.DESTINATION, bar)).andExpect(status().isOk());
        return bar;
    }

    private UUID heldPrinter(String prefix) throws Exception {
        UUID printer = fixtures.printer(shop.store(), prefix + UUID.randomUUID().toString().substring(0, 6),
                "127.0.0.1", 9);
        fixtures.mode(shop.store(), printer, "HOLD");
        return printer;
    }

    private ResultActions route(UUID ticket, String key, RequestPostProcessor actor) throws Exception {
        return mvc.perform(post(POS_TICKETS + "/" + ticket + "/route").header("Idempotency-Key", key).with(csrf())
                .with(actor));
    }

    private RequestPostProcessor operator() {
        return pos(shop.store(), employee, terminal, Permission.PRODUCTION_OPERATE);
    }

    private Map<String, Object> ticketRow(UUID ticket) {
        return jdbc.sql("SELECT * FROM production.production_ticket WHERE id = ?").param(ticket).query().singleRow();
    }

    private static String key() {
        return UUID.randomUUID().toString();
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
