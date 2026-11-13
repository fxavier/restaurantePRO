package com.xavier.backend.production;

import static com.xavier.backend.fiscal.FiscalActors.inStore;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.production.model.PrintLine;
import com.xavier.backend.production.model.StyledContent;
import com.xavier.backend.production.service.PrintWorker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Printers, routes and rounds for production tests, built through the BackOffice and POS HTTP APIs as people would,
 * plus reads of the production tables. The shop's item goes to the {@code BAR} destination.
 */
@TestComponent
public class ProductionFixtures {

    public static final String PRINTERS = "/api/v1/backoffice/printers";
    public static final String ROUTES = "/api/v1/backoffice/production-routes";
    public static final String TERMINAL_PRINTERS = "/api/v1/backoffice/terminal-printers";
    public static final String DESTINATION = "BAR";

    /** A round that was confirmed: its order, batch and the employee and terminal that confirmed it. */
    public record Round(UUID order, UUID batch, long orderNumber, long orderVersion) {
    }

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final OrdersFixtures orders;
    private final PrintWorker worker;

    ProductionFixtures(MockMvc mvc, JdbcClient jdbc, OrdersFixtures orders, PrintWorker worker) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.orders = orders;
        this.worker = worker;
    }

    public Shop shop() throws Exception {
        return orders.shop();
    }

    public ResultActions createPrinter(UUID store, String code, String host, int port) throws Exception {
        return mvc.perform(post(PRINTERS).with(inStore(store, Permission.PRODUCTION_MANAGE)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"storeId": "%s", "code": "%s", "name": "Impressora %s", "host": "%s", "port": %d,
                         "codePage": "CP860"}""".formatted(store, code, code, host, port)));
    }

    /** A printer with profile fields (D154), e.g. {@code "paperWidthMm": 58, "statusProbe": true}. */
    public UUID printer(UUID store, String code, String host, int port, String profileFields) throws Exception {
        return id(mvc.perform(post(PRINTERS).with(inStore(store, Permission.PRODUCTION_MANAGE)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"storeId": "%s", "code": "%s", "name": "Impressora %s", "host": "%s", "port": %d,
                         "codePage": "CP860", %s}""".formatted(store, code, code, host, port, profileFields)))
                .andExpect(status().isCreated()));
    }

    /** A test page queued on {@code printer} (D156); its job id. */
    public UUID testPage(UUID store, UUID printer) throws Exception {
        return id(mvc.perform(post(PRINTERS + "/" + printer + "/test").header("Idempotency-Key",
                        UUID.randomUUID().toString()).with(csrf()).with(inStore(store, Permission.PRODUCTION_MANAGE)))
                .andExpect(status().isCreated()));
    }

    public Map<String, Object> printerRow(UUID printer) {
        return jdbc.sql("SELECT * FROM production.printer WHERE id = ?").param(printer).query().singleRow();
    }

    public UUID printer(UUID store, String code, String host, int port) throws Exception {
        return id(createPrinter(store, code, host, port).andExpect(status().isCreated()));
    }

    /** A printer the shop's {@code BAR} destination is routed to. */
    public UUID barPrinter(UUID store, String host, int port) throws Exception {
        UUID printer = printer(store, "BAR" + UUID.randomUUID().toString().substring(0, 6), host, port);
        routes(store, Map.of(DESTINATION, printer)).andExpect(status().isOk());
        return printer;
    }

    public ResultActions routes(UUID store, Map<String, UUID> routes) throws Exception {
        String entries = String.join(",", routes.entrySet().stream().map(e -> """
                {"destinationCode": "%s", "printerId": "%s"}""".formatted(e.getKey(), e.getValue())).toList());
        return mvc.perform(put(ROUTES).with(inStore(store, Permission.PRODUCTION_MANAGE)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"storeId": "%s", "routes": [%s]}""".formatted(store, entries)));
    }

    public ResultActions terminalPrinters(UUID store, Map<UUID, UUID> assignments) throws Exception {
        String entries = String.join(",", assignments.entrySet().stream().map(e -> """
                {"terminalId": "%s", "printerId": "%s"}""".formatted(e.getKey(), e.getValue())).toList());
        return mvc.perform(put(TERMINAL_PRINTERS).with(inStore(store, Permission.PRODUCTION_MANAGE)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"storeId": "%s", "assignments": [%s]}""".formatted(store, entries)));
    }

    public ResultActions changeMode(UUID store, UUID printer, String mode, UUID redirect, Permission... permissions)
            throws Exception {
        return mvc.perform(put(PRINTERS + "/" + printer + "/mode").with(inStore(store, permissions)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"mode": "%s", "redirectPrinterId": %s, "version": %d}""".formatted(mode,
                        redirect == null ? "null" : "\"" + redirect + "\"", printerVersion(printer))));
    }

    public void mode(UUID store, UUID printer, String mode) throws Exception {
        changeMode(store, printer, mode, null, Permission.PRODUCTION_MANAGE, Permission.PRODUCTION_SUPPRESS)
                .andExpect(status().isOk());
    }

    /** Opens the shop's table and confirms one round of {@code quantity} of the item. */
    public Round confirmedRound(Shop shop, UUID employee, UUID terminal, String quantity) throws Exception {
        JsonNode order = orders.openedOrder(shop, employee, terminal);
        return nextRound(shop, UUID.fromString(order.get("id").asString()), order.get("orderNumber").asLong(),
                order.get("version").asLong(), employee, terminal, quantity);
    }

    /** A further round on {@code order}, whose current version is {@code orderVersion}. */
    public Round nextRound(Shop shop, UUID order, long orderNumber, long orderVersion, UUID employee, UUID terminal,
                           String quantity) throws Exception {
        UUID line = OrdersFixtures.id(orders.draft(shop, order, quantity, employee, terminal));
        JsonNode batch = OrdersFixtures.json(orders.confirm(shop, order, orderVersion, shop.catalogVersion(),
                        List.of(line), employee, terminal, UUID.randomUUID().toString())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Round(order, UUID.fromString(batch.get("id").asString()), orderNumber,
                batch.get("orderVersion").asLong());
    }

    public long printerVersion(UUID printer) {
        return jdbc.sql("SELECT version FROM production.printer WHERE id = ?").param(printer).query(Long.class)
                .single();
    }

    public List<Map<String, Object>> tickets(UUID sourceId) {
        return jdbc.sql("SELECT * FROM production.production_ticket WHERE source_id = ? ORDER BY created_at")
                .param(sourceId).query().listOfRows();
    }

    /**
     * A ticket row's printout as plain text lines joined by LF, whatever its stored format (D155): a STYLED_V1 rule
     * reads as 32 dashes and an amount line as "left right", as the R4 text did.
     */
    public static String text(Map<String, Object> ticket) {
        String content = (String) ticket.get("content");
        return "STYLED_V1".equals(ticket.get("content_format"))
                ? String.join("\n", StyledContent.fromJson(content).stream().map(PrintLine::plainText).toList())
                : content;
    }

    public List<Map<String, Object>> jobsOfSource(UUID sourceId) {
        return jdbc.sql("""
                        SELECT j.* FROM production.print_job j JOIN production.production_ticket t ON t.id = j.ticket_id
                        WHERE t.source_id = ? ORDER BY j.created_at""")
                .param(sourceId).query().listOfRows();
    }

    /** How many deliveries of an event about {@code source} production's listeners completed. */
    public long completedPublications(UUID source) {
        return jdbc.sql("""
                        SELECT count(*) FROM event_publication WHERE serialized_event LIKE :id
                          AND listener_id LIKE '%ProductionListeners%' AND completion_date IS NOT NULL""")
                .param("id", "%" + source + "%").query(Long.class).single();
    }

    public Map<String, Object> job(UUID job) {
        return jdbc.sql("SELECT * FROM production.print_job WHERE id = ?").param(job).query().singleRow();
    }

    public String jobStatus(UUID job) {
        return (String) job(job).get("status");
    }

    public String batchStatus(UUID batch) {
        return jdbc.sql("SELECT status FROM orders.order_batch WHERE id = ?").param(batch).query(String.class)
                .single();
    }

    public long audits(String action, UUID entity) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = ? AND entity_id = ?")
                .param(action).param(entity.toString()).query(Long.class).single();
    }

    /** Makes a waiting job due now (instead of sleeping through its backoff). */
    public void dueNow(UUID job) {
        jdbc.sql("UPDATE production.print_job SET next_attempt_at = now() - interval '1 second' WHERE id = ?")
                .param(job).update();
    }

    /** Runs the worker until {@code condition} holds (sends are asynchronous), for up to 20 s. */
    public void runWorkerUntil(BooleanSupplier condition, String what) throws InterruptedException {
        runWorkerUntil(condition, what, Duration.ofSeconds(20));
    }

    /** As {@link #runWorkerUntil(BooleanSupplier, String)}, for a send that is slow by design. */
    public void runWorkerUntil(BooleanSupplier condition, String what, Duration within) throws InterruptedException {
        long deadline = System.nanoTime() + within.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            worker.poll();
            Thread.sleep(50);
        }
    }

    /** Waits (without running the worker) up to 10 s; listeners run after the commit, on another thread. */
    public static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            Thread.sleep(50);
        }
    }

    public static UUID id(ResultActions result) throws Exception {
        return UUID.fromString(OrdersFixtures.json(result.andReturn().getResponse().getContentAsString()).get("id")
                .asString());
    }
}
