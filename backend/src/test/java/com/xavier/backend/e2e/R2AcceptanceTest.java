package com.xavier.backend.e2e;

import static com.xavier.backend.e2e.Installation.CASHIER_ROLE;
import static com.xavier.backend.e2e.Installation.MANAGER_ROLE;
import static com.xavier.backend.e2e.Installation.employee;
import static com.xavier.backend.e2e.Installation.id;
import static com.xavier.backend.e2e.Installation.posLogin;
import static com.xavier.backend.e2e.Installation.publishCatalog;
import static com.xavier.backend.e2e.Installation.terminal;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.e2e.Browser.Csrf;
import com.xavier.backend.production.TcpFakePrinter;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * R2 end to end (PRD S17, decisions §13): the Stage 2 service of one table through the real HTTP API of a real
 * embedded server, with the real security chain, CSRF, sessions in PostgreSQL, the production print worker sending
 * ESC/POS over TCP to {@link TcpFakePrinter}s, the internal (non-certified) fiscal adapter, and no mocks:
 * <ol>
 *   <li>R1 setup, then printers, destination routes, the terminal's printer, a cash register, a fiscal series and a
 *       card payment method (CASH is seeded);</li>
 *   <li>POS PIN login, a cash session, a table, drafts, round 1 (printed per destination), round 2 (only its line);</li>
 *   <li>a void with an approver (void ticket), a subtotal print;</li>
 *   <li>part in cash with change, the rest by external card with confirm, the document (printed, non-fiscal mark),
 *       the order closed and the table FREE;</li>
 *   <li>the session closed with counts, the closing created, submitted and approved, the day closed operationally,
 *       then financially.</li>
 * </ol>
 * Along the way (A14) a POS tab of store A follows its change stream and receives TABLES, ORDER and PRINT_JOBS
 * notices that carry only {@code topic} and {@code id}, while a POS tab of store B receives none.
 *
 * <p>Like {@link R1AcceptanceTest} it runs against its own empty PostgreSQL (a bean of this context, closed with it),
 * so the company and the administrator come from the bootstrap settings and every count below is this journey's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Installation.FreshDatabase.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R2AcceptanceTest {

    static final String INITIAL_PASSWORD = "initial " + UUID.randomUUID();
    static final String NEW_PASSWORD = "changed " + UUID.randomUUID();
    static final String CASHIER_PIN = "5172";
    static final String MANAGER_PIN = "6283";
    static final Charset CP860 = Charset.forName("IBM860");
    static final Duration PRINTED = Duration.ofSeconds(20);
    static final String POS = "/api/v1/pos";

    static final TcpFakePrinter KITCHEN = open();
    static final TcpFakePrinter BAR = open();
    static final TcpFakePrinter COUNTER = open();

    @DynamicPropertySource
    static void bootstrapSettings(DynamicPropertyRegistry registry) throws IOException {
        Installation.bootstrap(registry, "Restaurante R2", INITIAL_PASSWORD);
    }

    @AfterAll
    static void unplugPrinters() throws IOException {
        KITCHEN.close();
        BAR.close();
        COUNTER.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private final List<String> journey = new ArrayList<>();

    @Test
    void aTableIsServedPaidAndTheDayClosed() throws Exception {
        try {
            run();
        } finally {
            System.out.println("R2 acceptance journey (step -> HTTP status):\n  " + String.join("\n  ", journey));
        }
    }

    private void run() throws Exception {
        // ---- 1. R1 setup: admin, two stores, terminals, employees, a table and a published catalog ----
        Browser office = new Browser("backoffice", port, journey);
        Installation.firstLogin(office, INITIAL_PASSWORD, NEW_PASSWORD);
        UUID storeA = Installation.store(office, "A");
        UUID storeB = Installation.store(office, "B");
        UUID terminal1 = terminal(office, storeA, "POS1");
        UUID terminal2 = terminal(office, storeA, "POS2");
        UUID terminalB = terminal(office, storeB, "POSB");
        String cashierCode = employee(office, storeA, "CX1", "Carla", CASHIER_PIN, CASHIER_ROLE);
        String managerCode = employee(office, storeA, "GR1", "Gil", MANAGER_PIN, MANAGER_ROLE);
        String cashierBCode = employee(office, storeB, "CXB", "Beto", CASHIER_PIN, CASHIER_ROLE);
        UUID area = id(office.post("/api/v1/backoffice/areas", Map.of("storeId", storeA, "code", "SALA",
                "name", "Sala"), 201));
        UUID table = id(office.post("/api/v1/backoffice/tables", Map.of("storeId", storeA, "areaId", area,
                "code", "M1", "seats", 4), 201));
        Map<String, UUID> items = publishCatalog(office, storeA);

        // ---- 1b. R2 setup: printers, routes, the terminal's printer, register, series, card method ----
        UUID kitchen = printer(office, storeA, "COZ", KITCHEN);
        UUID bar = printer(office, storeA, "BAR", BAR);
        UUID counter = printer(office, storeA, "BALCAO", COUNTER);
        office.put("/api/v1/backoffice/production-routes", Map.of("storeId", storeA, "routes", List.of(
                Map.of("destinationCode", "COZINHA", "printerId", kitchen),
                Map.of("destinationCode", "BAR", "printerId", bar))), 200, "routes");
        office.put("/api/v1/backoffice/terminal-printers", Map.of("storeId", storeA, "assignments",
                List.of(Map.of("terminalId", terminal1, "printerId", counter))), 200, "terminal printer");
        UUID register = id(office.command("/api/v1/backoffice/cash/registers",
                Map.of("storeId", storeA, "code", "CX1", "name", "Caixa 1"), 201, "register"));
        UUID series = id(office.command("/api/v1/backoffice/fiscal/series",
                Map.of("storeId", storeA, "code", "FR", "firstNumber", 1), 201, "series"));
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("code", "CARD");
        card.put("name", "Cartao (TPA)");
        card.put("kind", "EXTERNAL_TERMINAL");
        card.put("providerCode", null);
        office.command("/api/v1/backoffice/payment-methods", card, 201, "card method");
        assertThat(audits("PRODUCTION_ROUTES_REPLACED", storeA)).isEqualTo(1);
        assertThat(audits("TERMINAL_PRINTERS_REPLACED", storeA)).isEqualTo(1);
        assertThat(audits("CASH_REGISTER_CREATED", register)).isEqualTo(1);
        assertThat(audits("FISCAL_SERIES_CREATED", series)).isEqualTo(1);

        // ---- 2. POS: PIN login, session, change streams, table, two rounds ----
        Browser cashier = posLogin(office, terminal1, cashierCode, CASHIER_PIN, "cashier");
        UUID cashierId = UUID.fromString(cashier.get("/api/v1/auth/session", 200).get("employeeId").asString());
        Browser cashierB = posLogin(office, terminalB, cashierBCode, CASHIER_PIN, "cashier B");
        try (ChangeStream streamA = ChangeStream.subscribe(cashier);
             ChangeStream streamB = ChangeStream.subscribe(cashierB)) {
            // positive controls: each stream hears its own store's dining change (repeated with fresh writes until it
            // does, as a subscription takes effect only once the server registered it), so both are live from here on.
            // A goes live first, so it is listening while B's controls commit.
            List<UUID> controlA = streamA.makeLive(attempt -> id(office.post("/api/v1/backoffice/tables",
                    Map.of("storeId", storeA, "areaId", area, "code", "C" + attempt, "seats", 2), 201)), "store A");
            List<UUID> controlB = new ArrayList<>(streamB.makeLive(attempt -> areaOf(office, storeB, "SALA" + attempt),
                    "store B"));
            assertThat(streamA.status()).isEqualTo(200);
            assertThat(streamA.contentType()).startsWith("text/event-stream");
            assertThat(streamB.status()).isEqualTo(200);

            JsonNode session = cashier.command(POS + "/cash/sessions",
                    Map.of("registerId", register, "openingFloat", new BigDecimal("100.00")), 201, "open session");
            UUID sessionId = id(session);
            UUID cashDay = UUID.fromString(session.get("cashDayId").asString());
            long catalogVersion = cashier.get(POS + "/catalog?storeId=" + storeA, 200).get("version").asLong();

            JsonNode opened = cashier.command(POS + "/tables/" + table + "/open", Map.of("guests", 2), 201,
                    "open table M1");
            UUID order = id(opened);
            long orderNumber = opened.get("orderNumber").asLong();
            String header = "Mesa M1 - Pedido #" + orderNumber;
            assertThat(tableStatus(cashier, storeA, table)).isEqualTo("OCCUPIED");

            UUID prego = line(cashier, order, items.get("PREGO"), "2");
            UUID sumo = line(cashier, order, items.get("SUMO"), "1");
            JsonNode round1 = cashier.command(POS + "/orders/" + order + "/batches", Map.of("orderVersion",
                    version(cashier, order), "catalogVersion", catalogVersion, "lineIds", List.of(prego, sumo)),
                    201, "confirm round 1");
            assertThat(round1.get("batchNo").asInt()).isEqualTo(1);
            List<String> kitchenRound1 = awaitPrinted(KITCHEN, header, s -> s.contains("Ronda 1"));
            assertThat(kitchenRound1.getFirst()).contains("COZINHA").contains("2 x Prego no pao")
                    .doesNotContain("Sumo");
            List<String> barRound1 = awaitPrinted(BAR, header, s -> s.contains("Ronda 1"));
            assertThat(barRound1.getFirst()).contains("1 x Sumo de manga").doesNotContain("Prego");

            UUID agua = line(cashier, order, items.get("AGUA"), "1");
            JsonNode round2 = cashier.command(POS + "/orders/" + order + "/batches", Map.of("orderVersion",
                    version(cashier, order), "catalogVersion", catalogVersion, "lineIds", List.of(agua)), 201,
                    "confirm round 2");
            UUID batch2 = id(round2);
            List<String> barRound2 = awaitPrinted(BAR, header, s -> s.contains("Ronda 2"));
            assertThat(barRound2.getFirst()).contains("1 x Agua 50cl").doesNotContain("Sumo").doesNotContain("Prego");
            // round 2 has no kitchen line: its batch made one ticket (bar), and the kitchen got only round 1
            assertThat(count("SELECT count(*) FROM production.production_ticket WHERE source_id = ?", batch2))
                    .isEqualTo(1);
            assertThat(KITCHEN.matching(CP860, s -> s.contains(header))).hasSize(1);
            assertThat(audits("ORDER_BATCH_CONFIRMED", order)).isEqualTo(2);

            // ---- 3. void with an approver, subtotal print ----
            Map<String, Object> voidBody = Map.of("orderVersion", version(cashier, order), "lineId", agua,
                    "quantity", new BigDecimal("1"), "reason", "Cliente desistiu", "wasted", false);
            cashier.command(POS + "/orders/" + order + "/voids", voidBody, 403, "void without approver (A6)");
            Map<String, String> approver = new LinkedHashMap<>(Browser.idem());
            approver.put("X-Approver-Code", managerCode);
            approver.put("X-Approver-Pin", MANAGER_PIN);
            JsonNode voided = cashier.post(POS + "/orders/" + order + "/voids", voidBody, 201, Csrf.SEND, approver,
                    "void with approver");
            UUID managerId = UUID.fromString(voided.get("approverEmployeeId").asString());
            assertThat(voided.get("operatorEmployeeId").asString()).isEqualTo(cashierId.toString());
            assertThat(managerId).isNotEqualTo(cashierId);
            assertThat(money(voided.get("order").get("due"))).isEqualByComparingTo("360.00");
            Map<String, Object> voidAudit = jdbc.sql("""
                            SELECT actor_id, approver_id, reason FROM audit.audit_event
                            WHERE action = 'ORDER_LINE_VOIDED' AND entity_id = ?""")
                    .param(voided.get("id").asString()).query().singleRow();
            assertThat(voidAudit).containsEntry("actor_id", cashierId).containsEntry("approver_id", managerId)
                    .containsEntry("reason", "Cliente desistiu");
            assertThat(awaitPrinted(BAR, header, s -> s.contains("ANULAÇÃO")).getFirst())
                    .contains("-1 x Agua 50cl").contains("Motivo: Cliente desistiu");

            JsonNode subtotal = cashier.post(POS + "/orders/" + order + "/subtotal/print", null, 202, Csrf.SEND,
                    Browser.idem(), "subtotal print");
            assertThat(money(subtotal.get("due"))).isEqualByComparingTo("360.00");
            assertThat(awaitPrinted(COUNTER, header, s -> s.contains("SUBTOTAL")).getFirst())
                    .contains("NÃO SERVE DE FATURA").contains("2 x Prego no pao 150.00 = 300.00")
                    .contains("TOTAL 360.00 MZN").doesNotContain("Agua");

            // ---- 4. pay in cash with change, the rest by card, issue the document, close the order ----
            office.command(POS + "/payment-attempts", Map.of("orderId", order, "methodCode", "CASH",
                    "amount", new BigDecimal("200.00")), 403, "a BackOffice session paying at the POS (A12)");
            Map<String, Object> cashBody = Map.of("orderId", order, "methodCode", "CASH",
                    "amount", new BigDecimal("200.00"), "tendered", new BigDecimal("250.00"));
            Map<String, String> cashKey = Browser.idem();
            HttpResponse<String> cash = cashier.send("POST", POS + "/payment-attempts", cashBody, Csrf.SEND, cashKey);
            cashier.record("cashier: pay 200.00 in cash, tendered 250.00", cash);
            assertThat(cash.statusCode()).as(cash.body()).isEqualTo(201);
            JsonNode cashAttempt = Browser.JSON.readTree(cash.body());
            assertThat(cashAttempt.get("status").asString()).isEqualTo("SUCCEEDED");
            assertThat(money(cashAttempt.get("change"))).isEqualByComparingTo("50.00");
            HttpResponse<String> replay = cashier.send("POST", POS + "/payment-attempts", cashBody, Csrf.SEND,
                    cashKey);
            cashier.record("cashier: the same cash payment again (same key)", replay);
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(Browser.JSON.readTree(replay.body())).isEqualTo(cashAttempt);

            JsonNode cardAttempt = cashier.command(POS + "/payment-attempts", Map.of("orderId", order,
                    "methodCode", "CARD", "amount", new BigDecimal("160.00")), 201, "pay 160.00 by card");
            assertThat(cardAttempt.get("status").asString()).isEqualTo("PENDING");
            JsonNode confirmed = cashier.command(POS + "/payment-attempts/" + id(cardAttempt) + "/confirm",
                    Map.of("authorizationCode", "TPA-778812"), 200, "confirm card");
            assertThat(confirmed.get("status").asString()).isEqualTo("SUCCEEDED");
            JsonNode settled = cashier.get(POS + "/orders/" + order + "/settlement", 200);
            assertThat(money(settled.get("paid"))).isEqualByComparingTo("360.00");
            assertThat(money(settled.get("balance"))).isEqualByComparingTo("0.00");
            assertThat(settled.get("financialState").asString()).isEqualTo("SETTLED");

            JsonNode document = cashier.command(POS + "/orders/" + order + "/documents", Map.of(), 201,
                    "issue document");
            assertThat(document.get("status").asString()).isEqualTo("ISSUED");
            assertThat(document.get("seriesCode").asString()).isEqualTo("FR");
            assertThat(document.get("number").asLong()).isEqualTo(1);
            assertThat(document.get("nonFiscal").asBoolean()).isTrue();
            assertThat(money(document.get("totalGross"))).isEqualByComparingTo("360.00");
            assertThat(awaitPrinted(COUNTER, "DOCUMENTO NÃO FISCAL", s -> true).getFirst())
                    .contains("Documento de venda FR/1").contains("Prego no pao").contains("360.00");

            JsonNode closed = cashier.command(POS + "/orders/" + order + "/close",
                    Map.of("orderVersion", cashier.get(POS + "/orders/" + order + "/settlement", 200)
                            .get("orderVersion").asLong()), 200, "close order");
            assertThat(closed.get("lifecycle").asString()).isEqualTo("CLOSED");
            assertThat(closed.get("fiscalState").asString()).isEqualTo("ISSUED");
            assertThat(tableStatus(cashier, storeA, table)).isEqualTo("FREE");
            assertThat(audits("ORDER_CLOSED", order)).isEqualTo(1);

            // exactly one Payment and one cash receipt per successful attempt, one document, no gap in the series
            assertThat(count("SELECT count(*) FROM settlement.payment WHERE order_id = ?", order)).isEqualTo(2);
            assertThat(count("SELECT count(DISTINCT attempt_id) FROM settlement.payment WHERE order_id = ?", order))
                    .isEqualTo(2);
            assertThat(count("""
                    SELECT count(*) FROM cash.cash_movement m JOIN settlement.payment p ON p.id = m.source_id
                    WHERE m.source_type = 'PAYMENT' AND p.order_id = ?""", order)).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM fiscal.fiscal_document WHERE source_id = ?", order)).isEqualTo(1);
            assertThat(count("SELECT next_number FROM fiscal.fiscal_series WHERE id = ?", series)).isEqualTo(2);
            assertThat(audits("PAYMENT_CONFIRMED", id(cashAttempt))).isEqualTo(1);
            assertThat(audits("PAYMENT_CONFIRMED", id(cardAttempt))).isEqualTo(1);

            // ---- 5. session close with counts, closing, operational then financial day close ----
            Browser manager = posLogin(office, terminal2, managerCode, MANAGER_PIN, "manager");
            JsonNode day = manager.get(POS + "/cash/days/current", 200);
            assertThat(day.get("id").asString()).isEqualTo(cashDay.toString());
            manager.command(POS + "/cash/days/" + cashDay + "/operational-close",
                    Map.of("version", day.get("version").asLong()), 422, "close the day with a session open (A11)");

            long sessionVersion = cashier.get(POS + "/cash/sessions/" + sessionId, 200).get("version").asLong();
            JsonNode sessionClosed = cashier.command(POS + "/cash/sessions/" + sessionId + "/close", Map.of(
                    "version", sessionVersion, "counts", List.of(
                            Map.of("methodCode", "CASH", "amount", new BigDecimal("300.00")),
                            Map.of("methodCode", "CARD", "amount", new BigDecimal("160.00")))),
                    200, "close session with counts");
            assertThat(sessionClosed.get("status").asString()).isEqualTo("CLOSED");

            JsonNode closing = cashier.command(POS + "/cash/closings", Map.of("registerId", register,
                    "cashDayId", cashDay), 201, "create closing");
            assertThat(closing.get("status").asString()).isEqualTo("DRAFT");
            assertThat(closing.get("lines")).isNotEmpty();
            closing.get("lines").forEach(closingLine -> assertThat(money(closingLine.get("difference")))
                    .as("difference of %s", closingLine).isEqualByComparingTo("0"));
            UUID closingId = id(closing);
            assertThat(cashier.command(POS + "/cash/closings/" + closingId + "/submit", null, 200, "submit closing")
                    .get("status").asString()).isEqualTo("SUBMITTED");
            assertThat(office.command("/api/v1/backoffice/cash/closings/" + closingId + "/approve", null, 200,
                    "approve closing").get("status").asString()).isEqualTo("APPROVED");

            JsonNode operational = manager.command(POS + "/cash/days/" + cashDay + "/operational-close",
                    Map.of("version", manager.get(POS + "/cash/days/current", 200).get("version").asLong()), 200,
                    "close the day operationally");
            assertThat(operational.get("status").asString()).isEqualTo("OPERATIONALLY_CLOSED");
            JsonNode financial = office.command("/api/v1/backoffice/cash/days/" + cashDay + "/financial-close",
                    null, 200, "close the day financially");
            assertThat(financial.get("status").asString()).isEqualTo("FINANCIALLY_CLOSED");
            assertThat(audits("CASH_SESSION_CLOSED", sessionId)).isEqualTo(1);
            assertThat(audits("CASH_CLOSING_APPROVED", closingId)).isEqualTo(1);
            assertThat(audits("CASH_DAY_OPERATIONALLY_CLOSED", cashDay)).isEqualTo(1);
            assertThat(audits("CASH_DAY_FINANCIALLY_CLOSED", cashDay)).isEqualTo(1);

            // ---- A14: store A's tab heard the commits, as {topic, id} only; store B's tab heard only its own ----
            // the table at open and at close, the order after each round, void and payment, and every print job: round 1
            // kitchen + bar, round 2 bar, the void, the subtotal, the document = 6 jobs (several notices each)
            assertThat(count("SELECT count(*) FROM production.print_job WHERE store_id = ?", storeA)).isEqualTo(6);
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                List<String> heard = topics(streamA);
                assertThat(heard).contains("ORDER:" + order, "PAYMENT_ATTEMPT:" + id(cashAttempt),
                        "PAYMENT_ATTEMPT:" + id(cardAttempt));
                assertThat(heard.stream().filter(("TABLES:" + table)::equals)).hasSizeGreaterThanOrEqualTo(2);
                assertThat(heard.stream().filter(t -> t.startsWith("PRINT_JOBS:")).distinct()).hasSize(6);
            });
            streamA.notices().forEach(notice -> assertThat(notice.propertyNames()).as("notice %s", notice)
                    .isSubsetOf("topic", "id"));
            journey.add("SSE store A: " + streamA.notices().size() + " notices, topics " + topics(streamA).stream()
                    .map(t -> t.contains(":") ? t.substring(0, t.indexOf(':')) : t).distinct().toList());

            // one more control of B after the whole journey: B is still live, and notices go out in commit order, so
            // anything of store A's that could have reached B has been delivered by now
            controlB.addAll(streamB.makeLive(attempt -> areaOf(office, storeB, "FIM" + attempt), "store B again"));
            assertThat(topics(streamA)).noneMatch(t -> controlB.stream().anyMatch(b -> t.contains(b.toString())));
            assertThat(streamB.notices()).isNotEmpty();
            assertThat(topics(streamB)).as("store B's stream: only its own controls")
                    .allMatch(t -> controlB.stream().anyMatch(b -> t.equals("TABLES:" + b)));
            journey.add("SSE store B: " + streamB.notices().size() + " notice(s), all its own controls ("
                    + controlB.size() + " write(s)); none of store A's");

            // ---- A14: a tab that reconnects gets no replay of what it missed, and re-reads through the GET ----
            List<String> heardBefore = ids(streamA);
            UUID missed;
            try (ChangeStream witness = ChangeStream.subscribe(cashier)) {
                witness.makeLive(attempt -> id(office.post("/api/v1/backoffice/tables",
                        Map.of("storeId", storeA, "areaId", area, "code", "W" + attempt, "seats", 2), 201)),
                        "store A witness");
                streamA.close();
                missed = id(office.post("/api/v1/backoffice/tables", Map.of("storeId", storeA, "areaId", area,
                        "code", "M9", "seats", 4), 201));
                // notices go out after commit through one in-order sender: once the witness has M9's, it can no
                // longer reach a later subscriber, so "replays nothing" doesn't depend on timing
                Awaitility.await().atMost(Duration.ofSeconds(10))
                        .untilAsserted(() -> assertThat(ids(witness)).contains(missed.toString()));
            }
            try (ChangeStream again = ChangeStream.subscribe(cashier)) {
                List<UUID> controlAgain = again.makeLive(attempt -> id(office.post("/api/v1/backoffice/tables",
                        Map.of("storeId", storeA, "areaId", area, "code", "R" + attempt, "seats", 2), 201)),
                        "store A after reconnecting");
                assertThat(ids(again)).as("the reconnected stream replays nothing")
                        .doesNotContain(missed.toString()).doesNotContainAnyElementsOf(heardBefore)
                        .allMatch(i -> controlAgain.stream().anyMatch(c -> c.toString().equals(i)));
                assertThat(tableStatus(cashier, storeA, missed)).as("the re-read shows the missed table")
                        .isEqualTo("FREE");
                journey.add("SSE store A reconnected: " + again.notices().size()
                        + " notice(s), only its new control; the missed table M9 came from GET /pos/tables");
            }
        }
    }

    private static TcpFakePrinter open() {
        try {
            return TcpFakePrinter.accepting();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID printer(Browser office, UUID store, String code, TcpFakePrinter device) throws Exception {
        return id(office.command("/api/v1/backoffice/printers", Map.of("storeId", store, "code", code,
                "name", "Impressora " + code, "host", device.host(), "port", device.port(), "codePage", "CP860"),
                201, "printer " + code));
    }

    private UUID line(Browser pos, UUID order, UUID item, String quantity) throws Exception {
        return id(pos.command(POS + "/orders/" + order + "/lines", Map.of("itemId", item,
                "quantity", new BigDecimal(quantity)), 201, "draft"));
    }

    private long version(Browser pos, UUID order) throws Exception {
        return pos.get(POS + "/orders/" + order, 200).get("version").asLong();
    }

    private String tableStatus(Browser pos, UUID store, UUID table) throws Exception {
        for (JsonNode area : pos.get(POS + "/tables?storeId=" + store, 200).get("areas")) {
            for (JsonNode t : area.get("tables")) {
                if (t.get("id").asString().equals(table.toString())) {
                    return t.get("status").asString();
                }
            }
        }
        throw new AssertionError("table " + table + " is not on the map");
    }

    /** The payloads the printer received that contain {@code marker} and match {@code also}; at least one. */
    private List<String> awaitPrinted(TcpFakePrinter device, String marker, Predicate<String> also)
            throws InterruptedException {
        Predicate<String> match = s -> s.contains(marker) && also.test(s);
        List<String> printed = device.awaitPayloads(CP860, match, 1, PRINTED);
        assertThat(printed).as("printed on port %d: %s", device.port(), marker).hasSize(1);
        return printed;
    }

    /** The ids the stream's notices named. */
    private static List<String> ids(ChangeStream stream) {
        return stream.notices().stream().filter(n -> n.hasNonNull("id")).map(n -> n.get("id").asString()).toList();
    }

    private static UUID areaOf(Browser office, UUID store, String code) throws Exception {
        return id(office.post("/api/v1/backoffice/areas", Map.of("storeId", store, "code", code, "name", "Sala " + code),
                201));
    }

    private static List<String> topics(ChangeStream stream) {
        return stream.notices().stream().map(n -> n.get("topic").asString()
                + (n.hasNonNull("id") ? ":" + n.get("id").asString() : "")).toList();
    }

    private long audits(String action, UUID entityId) {
        return count("SELECT count(*) FROM audit.audit_event WHERE action = ? AND entity_id = ?", action,
                entityId.toString());
    }

    private long count(String sql, Object... params) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        for (Object param : params) {
            statement = statement.param(param);
        }
        return statement.query(Long.class).single();
    }

    private static BigDecimal money(JsonNode node) {
        return new BigDecimal(node.get("amount").asString());
    }
}
