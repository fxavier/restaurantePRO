package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalActors;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.fiscal.adapter.ActiveFiscalAdapter;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.model.event.Line;
import com.xavier.backend.orders.model.event.OrderLinesVoided;
import com.xavier.backend.orders.model.event.SubtotalLine;
import com.xavier.backend.orders.model.event.SubtotalRequested;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import com.xavier.backend.production.model.CodePage;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Documents (original and copy), subtotals and voids, each consumed from its event once (D35). A10, reprint half:
 * a document reprint is a COPY ticket and job printed as a copy, and creates no document, payment or cash movement
 * (spec §8.4).
 */
@ProductionModuleTest
class TerminalAndVoidTicketsTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures fiscalFixtures;

    @Autowired
    FiscalApi fiscal;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    final UUID employee = UUID.randomUUID();
    /** Per test: the test instance is shared by the class's methods, and a terminal prints in one store. */
    UUID terminal;
    Shop shop;
    TcpFakePrinter printer;
    UUID counter;

    @BeforeEach
    void counterPrinter() throws Exception {
        terminal = UUID.randomUUID();
        shop = fixtures.shop();
        printer = TcpFakePrinter.accepting();
        counter = fixtures.printer(shop.store(), "CX" + UUID.randomUUID().toString().substring(0, 6), printer.host(),
                printer.port());
        fixtures.terminalPrinters(shop.store(), Map.of(terminal, counter)).andExpect(status().isOk());
    }

    @AfterEach
    void stopPrinter() throws Exception {
        printer.close();
    }

    @Test
    void anIssuedDocumentPrintsItsOriginalOnTheIssuingTerminalsPrinter() throws Exception {
        DocumentView document = issue();

        await(() -> fixtures.tickets(document.id()).size() == 1, "original ticket");
        assertThat(fixtures.tickets(document.id()).getFirst()).containsEntry("source_type", "DOCUMENT")
                .containsEntry("destination", "ORIGINAL").containsEntry("kind", "ORIGINAL")
                .containsEntry("printer_id", counter).containsEntry("terminal_id", terminal);
        UUID job = (UUID) fixtures.jobsOfSource(document.id()).getFirst().get("id");
        fixtures.runWorkerUntil(() -> "SENT".equals(fixtures.jobStatus(job)), "original sent");
        assertThat(printed(document, 1)).singleElement().satisfies(text -> assertThat(text)
                .contains(ActiveFiscalAdapter.NON_FISCAL_MARK, "Documento de venda A/").doesNotContain("CÓPIA"));
    }

    /** A10 (reprint half). */
    @Test
    void aDocumentReprintIsACopyJobPrintedAsACopyWithNoNewDocumentPaymentOrMovement() throws Exception {
        DocumentView document = issue();
        await(() -> fixtures.tickets(document.id()).size() == 1, "original ticket");
        long documents = count("SELECT count(*) FROM fiscal.fiscal_document WHERE store_id = ?");
        long movements = count("SELECT count(*) FROM cash.cash_movement WHERE store_id = ?");

        mvc.perform(post("/api/v1/pos/documents/" + document.id() + "/reprints")
                        .header("Idempotency-Key", UUID.randomUUID().toString()).with(csrf())
                        .with(FiscalActors.pos(shop.store(), employee, terminal, Permission.FISCAL_DOCUMENT_REPRINT)))
                .andExpect(status().isCreated());

        await(() -> fixtures.tickets(document.id()).size() == 2, "copy ticket");
        Map<String, Object> copy = fixtures.tickets(document.id()).getLast();
        assertThat(copy).containsEntry("destination", "COPY-1").containsEntry("kind", "COPY");
        Map<String, Object> copyJob = fixtures.jobsOfSource(document.id()).getLast();
        assertThat(copyJob).containsEntry("kind", "COPY").containsEntry("ticket_id", copy.get("id"));
        fixtures.runWorkerUntil(() -> fixtures.jobsOfSource(document.id()).stream()
                .allMatch(job -> "SENT".equals(job.get("status"))), "original and copy sent");
        assertThat(printed(document, 2)).filteredOn(text -> text.contains("CÓPIA")).singleElement()
                .satisfies(text -> assertThat(text.indexOf("CÓPIA")).isEqualTo(text.lastIndexOf("CÓPIA")));
        assertThat(count("SELECT count(*) FROM fiscal.fiscal_document WHERE store_id = ?")).isEqualTo(documents);
        assertThat(count("SELECT count(*) FROM cash.cash_movement WHERE store_id = ?")).isEqualTo(movements);

        mvc.perform(post("/api/v1/pos/production-tickets/" + copy.get("id") + "/reprints")
                        .header("Idempotency-Key", UUID.randomUUID().toString()).with(csrf())
                        .with(FiscalActors.pos(shop.store(), employee, terminal, Permission.PRODUCTION_OPERATE)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DOCUMENT_REPRINT_THROUGH_FISCAL"));
    }

    @Test
    void aSubtotalPrintsOnTheTerminalsPrinterOnceEvenWhenRedelivered() throws Exception {
        UUID order = UUID.randomUUID();
        SubtotalRequested subtotal = new SubtotalRequested(order, shop.store(), terminal, 77, "M1",
                List.of(new SubtotalLine("Coca-Cola 33cl", new BigDecimal("2"), Money.of("12.50", MZN),
                        Money.of("25.00", MZN))), Money.of("25.00", MZN));

        publish(subtotal);
        await(() -> fixtures.tickets(order).size() == 1, "subtotal ticket");
        publish(subtotal);

        Thread.sleep(500);
        assertThat(fixtures.tickets(order)).singleElement().satisfies(ticket -> {
            assertThat(ticket).containsEntry("source_type", "SUBTOTAL").containsEntry("printer_id", counter);
            assertThat((String) ticket.get("content")).contains("SUBTOTAL", "2 x Coca-Cola 33cl 12.50 = 25.00",
                    "TOTAL 25.00 MZN");
        });
        assertThat(fixtures.jobsOfSource(order)).hasSize(1);
    }

    @Test
    void aVoidPrintsAVoidTicketOnTheLinesDestinationOnce() throws Exception {
        UUID bar = fixtures.barPrinter(shop.store(), printer.host(), printer.port());
        UUID voidId = UUID.randomUUID();
        OrderLinesVoided voided = new OrderLinesVoided(UUID.randomUUID(), voidId, shop.store(), terminal, 77, "M1",
                new Line(UUID.randomUUID(), "I1", "Coca-Cola 33cl", null, new BigDecimal("3"), null, "BAR"),
                BigDecimal.ONE, "cliente desistiu", Instant.parse("2026-11-04T12:00:00Z"));

        publish(voided);
        await(() -> fixtures.tickets(voidId).size() == 1, "void ticket");
        publish(voided);

        Thread.sleep(500);
        assertThat(fixtures.tickets(voidId)).singleElement().satisfies(ticket -> {
            assertThat(ticket).containsEntry("source_type", "VOID").containsEntry("printer_id", bar);
            assertThat((String) ticket.get("content")).contains("ANULAÇÃO", "-1 x Coca-Cola 33cl",
                    "Motivo: cliente desistiu");
        });
        assertThat(fixtures.jobsOfSource(voidId)).hasSize(1);
    }

    /** A terminal mapped to a printer of another store is unrouted: the event completes, nothing is printed. */
    @Test
    void aTerminalMappedToAnotherStoresPrinterIsUnroutedAndTheEventCompletes() throws Exception {
        UUID otherStore = fixtures.shop().store();
        UUID order = UUID.randomUUID();
        double unroutedBefore = meters.get(ProductionMetrics.UNROUTED).counter().count();

        publish(new SubtotalRequested(order, otherStore, terminal, 79, null, List.of(), Money.of("0.00", MZN)));

        await(() -> completed(order), "publication completed");
        assertThat(fixtures.tickets(order)).isEmpty();
        assertThat(meters.get(ProductionMetrics.UNROUTED).counter().count()).isGreaterThan(unroutedBefore);
    }

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    private boolean completed(UUID source) {
        return jdbc.sql("""
                        SELECT count(*) FROM event_publication WHERE serialized_event LIKE :id
                          AND listener_id LIKE '%ProductionListeners%' AND completion_date IS NOT NULL""")
                .param("id", "%" + source + "%").query(Long.class).single() > 0;
    }

    @Test
    void aTerminalWithoutPrinterGetsNoTicket() throws Exception {
        UUID order = UUID.randomUUID();
        publish(new SubtotalRequested(order, shop.store(), UUID.randomUUID(), 78, null, List.of(),
                Money.of("0.00", MZN)));

        Thread.sleep(1000);
        assertThat(fixtures.tickets(order)).isEmpty();
    }

    private DocumentView issue() throws Exception {
        fiscalFixtures.newSeries(shop.store(), "A", 1);
        UUID draft = fiscalFixtures.newDraft(shop.store());
        return FiscalFixtures.asEmployee(shop.store(), employee, terminal, () -> fiscal.issue(draft, employee));
    }

    private List<String> printed(DocumentView document, int expected) throws InterruptedException {
        return printer.awaitPayloads(CodePage.CP860.charset(), text -> text.contains(
                "Documento de venda A/" + document.number()), expected, Duration.ofSeconds(5));
    }

    private void publish(Object event) {
        tx.executeWithoutResult(status -> events.publishEvent(event));
    }

    private long count(String sql) {
        return jdbc.sql(sql).param(shop.store()).query(Long.class).single();
    }
}
