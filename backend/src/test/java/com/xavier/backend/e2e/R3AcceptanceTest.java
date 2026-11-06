package com.xavier.backend.e2e;

import static com.xavier.backend.e2e.Installation.CASHIER_ROLE;
import static com.xavier.backend.e2e.Installation.MANAGER_ROLE;
import static com.xavier.backend.e2e.Installation.employee;
import static com.xavier.backend.e2e.Installation.id;
import static com.xavier.backend.e2e.Installation.posLogin;
import static com.xavier.backend.e2e.Installation.terminal;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.e2e.Browser.Csrf;
import com.xavier.backend.production.TcpFakePrinter;
import com.xavier.backend.settlement.FakePaymentProvider;
import com.xavier.backend.settlement.SettlementTestClock;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;
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
 * R3 end to end (PRD S33, r3-decisions §16 A16–A37): the Stage 3 money paths through the real HTTP API of a real
 * embedded server, with the real security chain, CSRF, sessions in PostgreSQL, the production print worker sending
 * ESC/POS over TCP to {@link TcpFakePrinter}s, the internal (non-certified) fiscal adapter, the integrated test provider
 * ({@link FakePaymentProvider}) and the shifted application clock ({@link SettlementTestClock}); nothing is mocked.
 * <ol>
 *   <li>R2 setup with two VAT rates (16 % and 23 %), a card method, an integrated method and a customer with a limit;</li>
 *   <li>table M1, two rounds; split by items (diners A and B) and the rest (100.00) in 3 equal parts (A16, A17);</li>
 *   <li>A pays by card and gets a partition document; B pays part in cash, the rest on account, which issues B's
 *       document at once with {@code Pagamento misto — Numerário: X; A crédito: Y} (A20, A36); a dessert round lands in
 *       the residual after A's document (A19); the parts and the residual are paid in cash;</li>
 *   <li>a settled void of the 23 % pool unit: beyond headroom 422, a refund (approver, A23), then within headroom (A24);
 *       the close issues the residual document, whose 23 % line absorbs the cent (D96, A35), Σ gross = due, Σ tax per
 *       rate = the order's (A16); the invoiced payment is not refundable (A23);</li>
 *   <li>4b. table M2 leaves the cent on its own issued partition document: the close succeeds, a PENDING_CORRECTION is
 *       listed by the documents control until a resolution is recorded (A35);</li>
 *   <li>table M3 on a second register: credit first (issued at once, {@code Saldo em aberto}), then an integrated
 *       payment that times out (UNKNOWN): reclassify too early 422, forced close 422, the poller resolves exactly one
 *       Payment (A25); the forced close then succeeds (A26); the document is unchanged and reprints the same block
 *       (A36);</li>
 *   <li>past the cutoff: a session opens in the new day with {@code PREVIOUS_DAY_OPEN} (P28), the debt is paid FIFO
 *       with a slip, a replay pays once, more than the debt 422 (A21);</li>
 *   <li>a card statement is imported (one match, one MISSING_INTERNAL, resolved); the same file again → one batch (A25);</li>
 *   <li>day 1: sessions closed, closings approved, operational and financial close, financial reopen, an adjustment,
 *       closing round 1 approved, financial close again (A26);</li>
 *   <li>reports equal the documents, receipts and credit, are {@code final}, and a rebuild from empty equals the live
 *       projection (A28); audit query, store scope, span guard and export (A27); a support grant for 2 h (A34).</li>
 * </ol>
 *
 * <p>Like {@link R2AcceptanceTest} it runs against its own empty PostgreSQL, so every count below is this journey's.
 * The counter printers use the WPC1252 table, the only one of {@code CodePage} with the D97 em dash (see
 * docs/qa/bugs/0001); the kitchen and bar keep CP860.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "restaurantpro.settlement.provider-timeout=PT2S",
        "restaurantpro.settlement.in-doubt-poll-interval=PT1S",
        "restaurantpro.identity.support-expiry-cron=* * * * * *"})
@Import({Installation.FreshDatabase.class, SettlementTestClock.Config.class, FakePaymentProvider.Config.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R3AcceptanceTest {

    static final String INITIAL_PASSWORD = "initial " + UUID.randomUUID();
    static final String NEW_PASSWORD = "changed " + UUID.randomUUID();
    static final String SUPPORT_PASSWORD = "support " + UUID.randomUUID();
    static final String CASHIER_PIN = "5172";
    static final String CASHIER2_PIN = "5839";
    static final String MANAGER_PIN = "6283";
    static final UUID WAITER_ROLE = UUID.fromString("01900000-0000-7000-8000-000000000303");
    static final UUID AUDITOR_ROLE = UUID.fromString("01900000-0000-7000-8000-000000000307");
    static final Charset CP860 = Charset.forName("IBM860");
    static final Charset WPC1252 = Charset.forName("windows-1252");
    static final Duration PRINTED = Duration.ofSeconds(20);
    static final String POS = "/api/v1/pos";
    static final String BO = "/api/v1/backoffice";
    static final String RECON = BO + "/reconciliation";
    static final String DASH = " — ";

    static final TcpFakePrinter KITCHEN = open();
    static final TcpFakePrinter BAR = open();
    static final TcpFakePrinter COUNTER = open();
    static final TcpFakePrinter COUNTER2 = open();

    @DynamicPropertySource
    static void bootstrapSettings(DynamicPropertyRegistry registry) throws IOException {
        Installation.bootstrap(registry, "Restaurante R3", INITIAL_PASSWORD);
    }

    @AfterAll
    static void unplugPrinters() throws IOException {
        KITCHEN.close();
        BAR.close();
        COUNTER.close();
        COUNTER2.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SettlementTestClock clock;

    @Autowired
    FakePaymentProvider provider;

    private final List<String> journey = new ArrayList<>();

    // the installation
    Browser office;
    UUID storeA;
    UUID storeB;
    UUID terminal1;
    UUID terminal2;
    UUID terminal3;
    String managerCode;
    String manager2Code;
    UUID area;
    Map<String, UUID> items;
    UUID register1;
    UUID register2;
    UUID customer;
    Browser cashier;
    Browser cashier2;
    Browser manager;
    UUID session1;
    UUID session2;
    UUID cashDay1;
    LocalDate day1;
    long catalogVersion;

    @Test
    void stageThreeMoneyPathsEndToEnd() throws Exception {
        try {
            setUp();
            tableOneSplitCreditVoidAndClose();
            tableTwoLeavesAPendingCorrection();
            tableThreeCreditFirstThenAPaymentInDoubt();
            nextDayDebtPayment();
            cardStatement();
            closeDayOneReopenAdjustAndApproveRoundOne();
            reportsEqualTheFacts();
            auditAndSupportAccess();
        } finally {
            clock.reset();
            System.out.println("R3 acceptance journey (step -> HTTP status):\n  " + String.join("\n  ", journey));
        }
    }

    // ---- 1. setup ----

    private void setUp() throws Exception {
        office = new Browser("backoffice", port, journey);
        Installation.firstLogin(office, INITIAL_PASSWORD, NEW_PASSWORD);
        storeA = Installation.store(office, "A");
        storeB = Installation.store(office, "B");
        terminal1 = terminal(office, storeA, "POS1");
        terminal2 = terminal(office, storeA, "POS2");
        terminal3 = terminal(office, storeA, "POS3");
        String cashierCode = employee(office, storeA, "CX1", "Carla", CASHIER_PIN, CASHIER_ROLE);
        String cashier2Code = employee(office, storeA, "CX2", "Dina", CASHIER2_PIN, CASHIER_ROLE);
        managerCode = employee(office, storeA, "GR1", "Gil", MANAGER_PIN, MANAGER_ROLE);
        manager2Code = employee(office, storeA, "GR2", "Helena", MANAGER_PIN, MANAGER_ROLE);
        area = id(office.post(BO + "/areas", Map.of("storeId", storeA, "code", "SALA", "name", "Sala"), 201));
        items = Installation.publishTwoRateCatalog(office, storeA);

        UUID kitchen = printer(storeA, "COZ", KITCHEN, "CP860");
        UUID bar = printer(storeA, "BAR", BAR, "CP860");
        UUID counter = printer(storeA, "BALCAO", COUNTER, "WPC1252");
        UUID counter2 = printer(storeA, "BALCAO2", COUNTER2, "WPC1252");
        office.put(BO + "/production-routes", Map.of("storeId", storeA, "routes", List.of(
                Map.of("destinationCode", "COZINHA", "printerId", kitchen),
                Map.of("destinationCode", "BAR", "printerId", bar))), 200, "routes");
        office.put(BO + "/terminal-printers", Map.of("storeId", storeA, "assignments", List.of(
                Map.of("terminalId", terminal1, "printerId", counter),
                Map.of("terminalId", terminal3, "printerId", counter2))), 200, "terminal printers");
        register1 = id(office.command(BO + "/cash/registers", Map.of("storeId", storeA, "code", "CX1",
                "name", "Caixa 1"), 201, "register 1"));
        register2 = id(office.command(BO + "/cash/registers", Map.of("storeId", storeA, "code", "CX2",
                "name", "Caixa 2"), 201, "register 2"));
        office.command(BO + "/fiscal/series", Map.of("storeId", storeA, "code", "FR", "firstNumber", 1), 201,
                "series");
        method("CARD", "Cartão (TPA)", "EXTERNAL_TERMINAL", null);
        method("MPESA", "M-Pesa", "INTEGRATED", FakePaymentProvider.CODE);

        customer = id(office.command(BO + "/customers", Map.of("name", "Joana Mucavele", "phone", "841234567",
                "taxId", "100200300"), 201, "customer"));
        office.command(BO + "/customers/" + customer + "/credit-limit-changes", Map.of("newLimit",
                new BigDecimal("200.00"), "reason", "Cliente habitual"), 200, "credit limit 200.00");

        cashier = posLogin(office, terminal1, cashierCode, CASHIER_PIN, "cashier");
        cashier2 = posLogin(office, terminal3, cashier2Code, CASHIER2_PIN, "cashier 2");
        manager = posLogin(office, terminal2, managerCode, MANAGER_PIN, "manager");
        JsonNode opened = cashier.command(POS + "/cash/sessions", Map.of("registerId", register1,
                "openingFloat", new BigDecimal("100.00")), 201, "open session 1");
        session1 = id(opened);
        cashDay1 = UUID.fromString(opened.get("cashDayId").asString());
        day1 = LocalDate.parse(opened.get("businessDate").asString());
        assertThat(opened.get("warnings")).isEmpty();
        session2 = id(cashier2.command(POS + "/cash/sessions", Map.of("registerId", register2,
                "openingFloat", new BigDecimal("0.00")), 201, "open session 2"));
        catalogVersion = cashier.get(POS + "/catalog?storeId=" + storeA, 200).get("version").asLong();
    }

    // ---- 2–4. table M1 ----

    private void tableOneSplitCreditVoidAndClose() throws Exception {
        UUID table = table("M1");
        JsonNode opened = cashier.command(POS + "/tables/" + table + "/open", Map.of("guests", 4), 201, "open M1");
        UUID order = id(opened);
        String header = "Mesa M1 - Pedido #" + opened.get("orderNumber").asLong();
        UUID prego = line(cashier, order, "PREGO", "2");
        UUID sumo = line(cashier, order, "SUMO", "2");
        UUID pao = line(cashier, order, "PAO", "2");
        UUID molho = line(cashier, order, "MOLHO", "1");
        confirm(cashier, order, List.of(prego, sumo, pao, molho), "M1 round 1");
        UUID agua = line(cashier, order, "AGUA", "1");
        confirm(cashier, order, List.of(agua), "M1 round 2");
        assertThat(awaitPrinted(KITCHEN, CP860, header, s -> s.contains("Ronda 1"))).contains("2 x Prego no pao");
        assertThat(money(settlement(cashier, order).get("due"))).isEqualByComparingTo("461.00");

        // A16/A18: two ITEMS partitions; a quantity already in a LIVE partition is refused
        JsonNode dinerA = cashier.command(POS + "/orders/" + order + "/partitions", Map.of("orderVersion",
                version(cashier, order), "kind", "ITEMS", "label", "Diner A", "lines", List.of(
                        lineQty(prego, "1"), lineQty(sumo, "1"), lineQty(pao, "1"))), 201, "partition A (items)");
        UUID partitionA = id(dinerA);
        assertThat(money(dinerA.get("due"))).isEqualByComparingTo("211.00");
        JsonNode dinerB = cashier.command(POS + "/orders/" + order + "/partitions", Map.of("orderVersion",
                version(cashier, order), "kind", "ITEMS", "label", "Diner B", "lines",
                List.of(lineQty(prego, "1"))), 201, "partition B (items)");
        UUID partitionB = id(dinerB);
        assertThat(money(dinerB.get("due"))).isEqualByComparingTo("150.00");
        assertThat(cashier.command(POS + "/orders/" + order + "/partitions", Map.of("orderVersion",
                version(cashier, order), "kind", "ITEMS", "lines", List.of(lineQty(prego, "1"))), 422,
                "a third PREGO that is already partitioned (A18)").get("code").asString())
                .isEqualTo("QUANTITY_ALREADY_PARTITIONED");

        // A17: the rest, 100.00, in 3 equal parts: 33.34 / 33.33 / 33.33
        JsonNode equal = cashier.command(POS + "/orders/" + order + "/partitions/equal", Map.of("orderVersion",
                version(cashier, order), "parts", 3), 201, "3 equal parts of the rest");
        List<String> parts = StreamSupport.stream(equal.spliterator(), false)
                .map(p -> money(p.get("due")).toPlainString()).toList();
        assertThat(parts).containsExactly("33.34", "33.33", "33.33");
        List<UUID> equalIds = StreamSupport.stream(equal.spliterator(), false).map(Installation::id).toList();

        // 3. diner A: card, then their own document
        JsonNode card = cashier.command(POS + "/payment-attempts", attempt(order, partitionA, "CARD", "211.00", null),
                201, "A pays 211.00 by card");
        cashier.command(POS + "/payment-attempts/" + id(card) + "/confirm", Map.of("authorizationCode", "TPA-A1"),
                200, "confirm card");
        JsonNode documentA = cashier.command(POS + "/partitions/" + partitionA + "/documents", Map.of(), 201,
                "partition A document");
        assertThat(documentA.get("status").asString()).isEqualTo("ISSUED");
        assertThat(money(documentA.get("totalGross"))).isEqualByComparingTo("211.00");
        assertThat(documentA.get("payments").get("status").asString()).isEqualTo("PAID");
        String printedA = awaitPrinted(COUNTER, WPC1252, "Documento de venda FR/" + documentA.get("number").asLong(),
                s -> true);
        assertThat(printedA).contains("Pagamento" + DASH + "Cartão (TPA): 211.00").doesNotContain("Saldo em aberto");

        // diner B: cash first, then the rest on account: B's document is issued at once (D97: MIXED)
        cashier.command(POS + "/payment-attempts", attempt(order, partitionB, "CASH", "100.00", "100.00"), 201,
                "B pays 100.00 in cash");
        JsonNode charge = cashier.command(POS + "/orders/" + order + "/credit-charges", Map.of("customerId", customer,
                "amount", new BigDecimal("50.00"), "partitionId", partitionB), 201, "B puts 50.00 on account");
        assertThat(charge.get("status").asString()).isEqualTo("POSTED");
        UUID documentB = UUID.fromString(charge.get("documentId").asString());
        JsonNode docB = cashier.get(POS + "/documents/" + documentB, 200);
        assertThat(docB.get("onAccount").asBoolean()).isTrue();
        assertThat(docB.get("payments").get("status").asString()).isEqualTo("MIXED");
        assertThat(docB.get("buyer").get("name").asString()).isEqualTo("Joana Mucavele");
        String printedB = awaitPrinted(COUNTER, WPC1252, "Documento de venda FR/" + docB.get("number").asLong(),
                s -> true);
        assertThat(printedB).contains("Pagamento misto" + DASH + "Numerário: 100.00; A crédito: 50.00")
                .doesNotContain("A CRÉDITO").doesNotContain("Saldo em aberto");
        // A20: the credit share is no cash movement and no Payment
        assertThat(count("""
                SELECT count(*) FROM cash.cash_movement m JOIN settlement.payment p ON p.id = m.source_id
                WHERE p.partition_id = ?""", partitionB)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM settlement.payment WHERE partition_id = ?", partitionB)).isEqualTo(1);

        // A19: a dessert round after A's and B's documents goes to the residual; the issued documents are unchanged
        UUID bolo = line(cashier, order, "BOLO", "1");
        confirm(cashier, order, List.of(bolo), "M1 dessert round");
        assertThat(cashier.get(POS + "/documents/" + id(documentA), 200).get("lines")).hasSize(3);
        assertThat(money(settlement(cashier, order).get("residualBalance"))).as("the ORDER target: the dessert; the parts cover the rest").isEqualByComparingTo("45.00");

        // the equal parts and the residual in cash; a second terminal paying the same part is refused (A17 is the
        // module IT PartitionDoublePaymentTest; here one replay with the same key pays once)
        String[] amounts = {"33.34", "33.33", "33.33"};
        for (int i = 0; i < 3; i++) {
            Map<String, String> key = Browser.idem();
            Map<String, Object> body = attempt(order, equalIds.get(i), "CASH", amounts[i], amounts[i]);
            JsonNode paid = cashier.post(POS + "/payment-attempts", body, 201, Csrf.SEND, key, "pay part " + (i + 1));
            assertThat(cashier.post(POS + "/payment-attempts", body, 201, Csrf.SEND, key, "replay part " + (i + 1)))
                    .isEqualTo(paid);
        }
        JsonNode residualCash = cashier.command(POS + "/payment-attempts", attempt(order, null, "CASH", "45.00",
                "50.00"), 201, "residual 45.00 in cash");
        assertThat(money(residualCash.get("change"))).isEqualByComparingTo("5.00");
        UUID residualPayment = paymentOf(id(residualCash));

        // 4. the settled void of the 23 % pool unit: beyond headroom first (A24)
        Map<String, Object> voidBody = Map.of("orderVersion", version(cashier, order), "lineId", pao,
                "quantity", new BigDecimal("1"), "reason", "Pao a mais", "wasted", false);
        assertThat(cashier.post(POS + "/orders/" + order + "/settlement/voids", voidBody, 422, Csrf.SEND,
                approver(), "void beyond headroom (A24)").get("code").asString()).isEqualTo("VOID_EXCEEDS_UNPAID");
        // A23: a cash refund before the document, with an approver: one REFUND OUT in the refunder's session
        cashier.post(POS + "/payments/" + residualPayment + "/refunds", Map.of("amount", new BigDecimal("1.00"),
                "reason", "Pao devolvido"), 403, Csrf.SEND, Browser.idem(), "refund without approver");
        JsonNode refund = cashier.post(POS + "/payments/" + residualPayment + "/refunds", Map.of("amount",
                new BigDecimal("1.00"), "reason", "Pao devolvido"), 201, Csrf.SEND, approver(), "refund 1.00");
        assertThat(refund.get("sessionId").asString()).isEqualTo(session1.toString());
        assertThat(count("SELECT count(*) FROM cash.cash_movement WHERE source_id = ? AND direction = 'OUT'",
                id(refund))).isEqualTo(1);
        assertThat(audits("PAYMENT_REFUNDED", id(refund))).isEqualTo(1);
        JsonNode voided = cashier.post(POS + "/orders/" + order + "/settlement/voids", Map.of("orderVersion",
                version(cashier, order), "lineId", pao, "quantity", new BigDecimal("1"), "reason", "Pao a mais",
                "wasted", false), 201, Csrf.SEND, approver(), "void within headroom (A24)");
        assertThat(money(voided.get("gross"))).isEqualByComparingTo("1.00");
        assertThat(awaitPrinted(KITCHEN, CP860, header, s -> s.contains("ANULAÇÃO"))).contains("-1 x Pao");
        // A24: the invoiced PREGO quantity cannot be voided
        assertThat(cashier.post(POS + "/orders/" + order + "/settlement/voids", Map.of("orderVersion",
                version(cashier, order), "lineId", prego, "quantity", new BigDecimal("1"), "reason", "x",
                "wasted", false), 422, Csrf.SEND, approver(), "void an invoiced quantity").get("code").asString())
                .isEqualTo("CORRECTION_REQUIRES_FISCAL_ADAPTER");

        // close: the residual document; its 23 % MOLHO line absorbs the cent (D96, A35)
        JsonNode closed = cashier.command(POS + "/orders/" + order + "/close", Map.of("orderVersion",
                settlement(cashier, order).get("orderVersion").asLong()), 200, "close M1");
        assertThat(closed.get("lifecycle").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("financialState").asString()).isEqualTo("SETTLED");
        UUID residual = UUID.fromString(closed.get("documentId").asString());
        JsonNode residualDoc = cashier.get(POS + "/documents/" + residual, 200);
        assertThat(money(residualDoc.get("totalGross"))).isEqualByComparingTo("144.00");
        assertThat(residualDoc.get("payments").get("status").asString()).isEqualTo("PAID");
        JsonNode molhoLine = StreamSupport.stream(residualDoc.get("lines").spliterator(), false)
                .filter(l -> l.get("sourceLineId").asString().equals(molho.toString())).findFirst().orElseThrow();
        assertThat(money(molhoLine.get("tax"))).isEqualByComparingTo("0.38");
        assertThat(money(molhoLine.get("gross"))).isEqualByComparingTo("2.00");
        JsonNode adjustments = closed.get("taxRoundingAdjustments");
        assertThat(adjustments).hasSize(1);
        assertThat(adjustments.get(0).get("kind").asString()).isEqualTo("ABSORBED");
        assertThat(adjustments.get(0).get("targetLineId").isNull()).isFalse();
        assertThat(audits("TAX_ROUNDING_ABSORBED", order)).isEqualTo(1);
        assertThat(printed(COUNTER, WPC1252, "Documento de venda FR/" + residualDoc.get("number").asLong()))
                .contains("Pagamento" + DASH + "Numerário: 144.00");

        // A16: Σ gross of the 3 documents = due; Σ tax per rate = the order's; Σ quantity per line = net quantity
        assertThat(money(closed.get("due"))).isEqualByComparingTo("505.00");
        assertThat(count("SELECT count(*) FROM fiscal.fiscal_document WHERE order_id = ? AND status = 'ISSUED'",
                order)).isEqualTo(3);
        assertThat(decimal("SELECT sum(total_gross) FROM fiscal.fiscal_document WHERE order_id = ? "
                + "AND status = 'ISSUED'", order)).isEqualByComparingTo("505.00");
        assertThat(documentTaxPerRate(order)).isEqualTo(orderTaxPerRate(order));
        assertThat(documentTaxPerRate(order)).containsEntry("23.00", new BigDecimal("0.56"));
        assertThat(documentQuantityPerLine(order)).isEqualTo(netQuantityPerLine(order));

        // A23: once invoiced (and not overpaid) the residual payment needs a corrective document
        assertThat(cashier.post(POS + "/payments/" + residualPayment + "/refunds", Map.of("amount",
                new BigDecimal("1.00"), "reason", "x"), 422, Csrf.SEND, approver(), "refund an invoiced payment")
                .get("code").asString()).isEqualTo("CORRECTION_REQUIRES_FISCAL_ADAPTER");
        journey.add("M1: 3 documents (A card, B mixed, residual), gross 505.00, 23% tax 0.56, cent ABSORBED");
    }

    // ---- 4b. table M2: the pending fiscal correction ----

    private void tableTwoLeavesAPendingCorrection() throws Exception {
        UUID table = table("M2");
        UUID order = id(cashier.command(POS + "/tables/" + table + "/open", Map.of("guests", 2), 201, "open M2"));
        UUID pao = line(cashier, order, "PAO", "2");
        UUID prego = line(cashier, order, "PREGO", "1");
        confirm(cashier, order, List.of(pao, prego), "M2 round 1");
        UUID partition = id(cashier.command(POS + "/orders/" + order + "/partitions", Map.of("orderVersion",
                version(cashier, order), "kind", "ITEMS", "lines", List.of(lineQty(pao, "1"))), 201,
                "M2 partition (1 PAO)"));
        cashier.command(POS + "/payment-attempts", attempt(order, partition, "CASH", "1.00", "1.00"), 201,
                "M2 partition in cash");
        JsonNode partitionDoc = cashier.command(POS + "/partitions/" + partition + "/documents", Map.of(), 201,
                "M2 partition document");
        UUID partitionDocument = id(partitionDoc);
        assertThat(money(partitionDoc.get("totalTax"))).isEqualByComparingTo("0.18");
        cashier.post(POS + "/orders/" + order + "/settlement/voids", Map.of("orderVersion", version(cashier, order),
                "lineId", pao, "quantity", new BigDecimal("1"), "reason", "Pao a mais", "wasted", true), 201,
                Csrf.SEND, approver(), "M2 void of the pool PAO");
        cashier.command(POS + "/payment-attempts", attempt(order, null, "CASH", "150.00", "150.00"), 201,
                "M2 residual in cash");
        JsonNode closed = cashier.command(POS + "/orders/" + order + "/close", Map.of("orderVersion",
                settlement(cashier, order).get("orderVersion").asLong()), 200, "close M2 (D96 pending case)");
        assertThat(closed.get("lifecycle").asString()).isEqualTo("CLOSED");
        JsonNode adjustment = closed.get("taxRoundingAdjustments").get(0);
        assertThat(adjustment.get("kind").asString()).isEqualTo("PENDING_CORRECTION");
        assertThat(adjustment.get("documentId").asString()).isEqualTo(partitionDocument.toString());
        assertThat(money(adjustment.get("amount"))).isEqualByComparingTo("0.01");
        assertThat(audits("FISCAL_CORRECTION_PENDING", order)).isEqualTo(1);
        // the issued partition document is unchanged
        assertThat(cashier.get(POS + "/documents/" + partitionDocument, 200)).isEqualTo(partitionDoc);

        // the documents control lists TAX_CORRECTION_PENDING (and nothing else for the day) until it is resolved
        JsonNode control = office.get(RECON + "/documents?storeId=" + storeA + "&businessDate=" + day1, 200);
        assertThat(kinds(control.get("exceptions"))).containsExactly("TAX_CORRECTION_PENDING");
        assertThat(control.get("exceptions").get(0).get("documentId").asString())
                .isEqualTo(partitionDocument.toString());
        JsonNode open = office.get(RECON + "/tax-corrections?storeId=" + storeA + "&open=true", 200);
        assertThat(open.get("content")).hasSize(1);
        UUID adjustmentId = id(open.get("content").get(0));
        JsonNode resolved = office.command(RECON + "/tax-corrections/" + adjustmentId + "/resolutions", Map.of(
                "correctionReference", "NC-2026-0001", "reason", "Nota de credito emitida"), 201,
                "resolve the pending correction");
        assertThat(resolved.get("resolution").get("correctionReference").asString()).isEqualTo("NC-2026-0001");
        assertThat(audits("TAX_CORRECTION_RESOLVED", adjustmentId)).isEqualTo(1);
        JsonNode after = office.get(RECON + "/documents?storeId=" + storeA + "&businessDate=" + day1, 200);
        assertThat(after.get("exceptions")).isEmpty();
        assertThat(after.get("roundingDifferences")).hasSize(1);
        assertThat(after.get("roundingDifferences").get(0).get("correctionReference").asString())
                .isEqualTo("NC-2026-0001");
        this.pendingOrder = order;
    }

    UUID pendingOrder;

    // ---- table M3: credit first, then a payment in doubt; forced close ----

    private void tableThreeCreditFirstThenAPaymentInDoubt() throws Exception {
        UUID table = table("M3");
        UUID order = id(cashier2.command(POS + "/tables/" + table + "/open", Map.of("guests", 1), 201, "open M3"));
        UUID prego = line(cashier2, order, "PREGO", "1");
        confirm(cashier2, order, List.of(prego), "M3 round 1");
        JsonNode charge = cashier2.command(POS + "/orders/" + order + "/credit-charges", Map.of("customerId",
                customer, "amount", new BigDecimal("100.00")), 201, "M3: 100.00 on account first");
        UUID document = UUID.fromString(charge.get("documentId").asString());
        JsonNode issued = cashier2.get(POS + "/documents/" + document, 200);
        assertThat(issued.get("status").asString()).isEqualTo("ISSUED");
        assertThat(issued.get("payments").get("status").asString()).isEqualTo("MIXED");
        assertThat(money(issued.get("payments").get("outstanding"))).isEqualByComparingTo("50.00");
        String printed = awaitPrinted(COUNTER2, WPC1252, "Documento de venda FR/" + issued.get("number").asLong(),
                s -> true);
        assertThat(printed).contains("Pagamento misto" + DASH + "A crédito: 100.00", "Saldo em aberto: 50.00")
                .doesNotContain("A CRÉDITO");

        // the rest by the integrated provider, which takes the money but answers too late: UNKNOWN
        provider.willNext(FakePaymentProvider.Behaviour.HANG);
        JsonNode inDoubt = cashier2.command(POS + "/payment-attempts", attempt(order, null, "MPESA", "50.00", null),
                202, "M3: 50.00 by M-Pesa (times out)");
        UUID attempt = id(inDoubt);
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> attemptStatus(cashier2, attempt), "UNKNOWN"::equals);
        provider.willNext(FakePaymentProvider.Behaviour.SUCCEED);
        assertThat(office.get(RECON + "/in-doubt?storeId=" + storeA, 200)).anySatisfy(a ->
                assertThat(a.get("attemptId").asString()).isEqualTo(attempt.toString()));
        // A25: reclassifying to FAILED this early is refused (a cashier lacks PAYMENT_RESOLVE: the manager asks, a
        // second manager approves)
        Map<String, String> approverHeaders = new LinkedHashMap<>(Browser.idem());
        approverHeaders.put("X-Approver-Code", manager2Code);
        approverHeaders.put("X-Approver-Pin", MANAGER_PIN);
        assertThat(manager.post(POS + "/payment-attempts/" + attempt + "/resolve", Map.of("outcome", "FAILED",
                "reason", "nao chegou"), 422, Csrf.SEND, approverHeaders, "reclassify FAILED too early")
                .get("code").asString()).isEqualTo("RECLASSIFY_TOO_EARLY");
        // A26: the abandoned session cannot be force-closed while a payment in it is in doubt
        assertThat(manager.command(POS + "/cash/sessions/" + session2 + "/force-close", Map.of("version",
                cashier2.get(POS + "/cash/sessions/" + session2, 200).get("version").asLong(), "counts",
                List.of(counted("CASH", "0.00")), "reason", "Caixa abandonada"), 409,
                "force-close with a payment in doubt").get("code").asString()).isEqualTo("PAYMENT_IN_DOUBT");

        // the poller asks the provider once the attempt is a minute old: exactly one Payment, as the system
        clock.advance(Duration.ofMinutes(2));
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> attemptStatus(cashier2, attempt), "SUCCEEDED"::equals);
        assertThat(count("SELECT count(*) FROM settlement.payment WHERE attempt_id = ?", attempt)).isEqualTo(1);
        assertThat(provider.charges(attempt.toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'PAYMENT_ATTEMPT_RESOLVED' "
                + "AND entity_id = ? AND actor_kind = 'SYSTEM'", attempt.toString())).isEqualTo(1);

        // the live status follows; the document keeps the position at issue and reprints the same block
        JsonNode settled = settlement(cashier2, order);
        assertThat(settled.get("paymentStatus").asString()).isEqualTo("MIXED");
        assertThat(settled.get("financialState").asString()).isEqualTo("SETTLED");
        cashier2.command(POS + "/orders/" + order + "/close", Map.of("orderVersion",
                settled.get("orderVersion").asLong()), 200, "close M3");
        assertThat(cashier2.get(POS + "/documents/" + document, 200).get("payments")).isEqualTo(issued.get("payments"));
        cashier2.command(POS + "/documents/" + document + "/reprints", Map.of(), 201, "reprint M3's document");
        List<String> copies = COUNTER2.awaitPayloads(WPC1252, s -> s.contains("Documento de venda FR/"
                + issued.get("number").asLong()), 2, PRINTED);
        assertThat(copies).hasSize(2);
        assertThat(copies.get(1)).contains("CÓPIA", "Pagamento misto" + DASH + "A crédito: 100.00",
                "Saldo em aberto: 50.00");

        // A26: now the manager force-closes the abandoned session with a count and a reason (audited)
        JsonNode abandoned = cashier2.get(POS + "/cash/sessions/" + session2, 200);
        assertThat(abandoned.get("expected")).anySatisfy(e -> {
            assertThat(e.get("methodCode").asString()).isEqualTo("MPESA");
            assertThat(money(e.get("amount"))).isEqualByComparingTo("50.00");
        });
        JsonNode forced = manager.command(POS + "/cash/sessions/" + session2 + "/force-close", Map.of("version",
                abandoned.get("version").asLong(), "counts", expectedCounts(abandoned), "reason", "Caixa abandonada"),
                200, "force-close session 2");
        assertThat(forced.get("status").asString()).isEqualTo("CLOSED");
        assertThat(audits("CASH_SESSION_FORCE_CLOSED", session2)).isEqualTo(1);
    }

    // ---- 5. the next day: P28 warning and the debt payment ----

    private void nextDayDebtPayment() throws Exception {
        moveToNextBusinessDay();
        JsonNode session = cashier.get(POS + "/cash/sessions/" + session1, 200);
        // the drawer is 5.00 short (bread bought from it and not recorded): justified at the closing, corrected later
        List<Map<String, Object>> counts = new ArrayList<>(expectedCounts(session));
        counts.replaceAll(c -> c.get("methodCode").equals("CASH") ? counted("CASH",
                ((BigDecimal) c.get("amount")).subtract(new BigDecimal("5.00")).toPlainString()) : c);
        assertThat(session.get("expected")).anySatisfy(e -> {
            assertThat(e.get("methodCode").asString()).isEqualTo("CASH");
            // float 100 + B 100 + parts 100 + residual 45 − refund 1 + M2 151; credit is no cash (A20)
            assertThat(money(e.get("amount"))).isEqualByComparingTo("495.00");
        });
        cashier.command(POS + "/cash/sessions/" + session1 + "/close", Map.of("version",
                session.get("version").asLong(), "counts", counts), 200, "close session 1 (day 1), 5.00 short");
        JsonNode next = cashier.command(POS + "/cash/sessions", Map.of("registerId", register1,
                "openingFloat", new BigDecimal("50.00")), 201, "open a session in day 2 (P28)");
        assertThat(next.get("businessDate").asString()).isNotEqualTo(day1.toString());
        assertThat(next.get("warnings")).anySatisfy(w -> assertThat(w.asString()).isEqualTo("PREVIOUS_DAY_OPEN"));

        // A21: the debt (50.00 + 100.00) is paid in part, FIFO, once per key; more than the debt is refused
        Map<String, String> key = Browser.idem();
        Map<String, Object> body = Map.of("methodCode", "CASH", "amount", new BigDecimal("120.00"),
                "tendered", new BigDecimal("120.00"));
        JsonNode paid = cashier.post(POS + "/customers/" + customer + "/credit-payments", body, 201, Csrf.SEND, key,
                "debt payment 120.00");
        assertThat(cashier.post(POS + "/customers/" + customer + "/credit-payments", body, 201, Csrf.SEND, key,
                "the same debt payment again (same key)")).isEqualTo(paid);
        assertThat(count("SELECT count(*) FROM settlement.payment WHERE attempt_id = ?", id(paid))).isEqualTo(1);
        assertThat(cashier.command(POS + "/customers/" + customer + "/credit-payments", Map.of("methodCode", "CASH",
                "amount", new BigDecimal("30.01"), "tendered", new BigDecimal("30.01")), 422,
                "pay more than the debt").get("code").asString()).isEqualTo("AMOUNT_EXCEEDS_DEBT");
        String slip = awaitPrinted(COUNTER, WPC1252, "PAGAMENTO DE CONTA CORRENTE", s -> true);
        assertThat(slip).contains("Valor pago: 120.00", "Divida restante: 30.00", "Cliente: Joana Mucavele");
        JsonNode statement = office.get(BO + "/customers/" + customer + "/statement", 200);
        assertThat(money(statement.get("closingBalance"))).isEqualByComparingTo("30.00");
        List<JsonNode> lines = StreamSupport.stream(statement.get("lines").spliterator(), false).toList();
        assertThat(lines.stream().map(l -> money(l.get("balance")).toPlainString()))
                .containsExactly("50.00", "150.00", "30.00");
        assertThat(money(lines.get(0).get("open"))).as("FIFO: the oldest debit is settled first")
                .isEqualByComparingTo("0.00");
        assertThat(money(lines.get(1).get("open"))).isEqualByComparingTo("30.00");
    }

    // ---- 6. the card statement ----

    private void cardStatement() throws Exception {
        Map<String, Object> card = jdbc.sql("""
                        SELECT p.confirmed_at, a.authorization_code FROM settlement.payment p
                        JOIN settlement.payment_attempt a ON a.id = p.attempt_id
                        WHERE p.store_id = ? AND p.method_code = 'CARD'""").param(storeA).query().singleRow();
        Instant confirmedAt = ((java.sql.Timestamp) card.get("confirmed_at")).toInstant();
        String csv = "occurred_at,amount,fee,reference,authorization_code,line_ref\n"
                + confirmedAt.atOffset(java.time.ZoneOffset.UTC) + ",211.00,3.17,,TPA-A1,L1\n"
                + confirmedAt.plusSeconds(60).atOffset(java.time.ZoneOffset.UTC) + ",15.00,0.23,,TPA-ZZ9,L2\n";
        Map<String, String> fields = Map.of("storeId", storeA.toString(), "methodCode", "CARD",
                "periodFrom", day1.toString(), "periodTo", day1.toString());
        JsonNode batch = office.upload(RECON + "/batches", fields, "tpa.csv", csv.getBytes(StandardCharsets.UTF_8),
                201, Browser.idem(), "import the card statement");
        assertThat(batch.get("entries").get("MATCHED").asLong()).isEqualTo(1);
        assertThat(batch.get("entries").get("MISSING_INTERNAL").asLong()).isEqualTo(1);
        long moneyFacts = count("SELECT (SELECT count(*) FROM settlement.payment) + (SELECT count(*) FROM "
                + "cash.cash_movement) + (SELECT count(*) FROM settlement.refund)");
        JsonNode again = office.upload(RECON + "/batches", fields, "tpa.csv", csv.getBytes(StandardCharsets.UTF_8),
                409, Browser.idem(), "the same statement again (another key)");
        assertThat(again.get("code").asString()).isEqualTo("STATEMENT_ALREADY_IMPORTED");
        assertThat(again.get("batchId").asString()).isEqualTo(batch.get("id").asString());
        assertThat(count("SELECT count(*) FROM settlement.reconciliation_batch")).isEqualTo(1);
        JsonNode missing = office.get(RECON + "/batches/" + id(batch) + "/entries?kind=MISSING_INTERNAL", 200);
        UUID entry = id(missing.get("content").get(0));
        office.command(RECON + "/entries/" + entry + "/resolutions", Map.of("action", "ESCALATED",
                "reason", "Nao e desta loja"), 201, "resolve the mismatch");
        assertThat(office.get(RECON + "/batches/" + id(batch) + "/entries?open=true", 200).get("content")).isEmpty();
        assertThat(count("SELECT (SELECT count(*) FROM settlement.payment) + (SELECT count(*) FROM "
                + "cash.cash_movement) + (SELECT count(*) FROM settlement.refund)")).as("no money fact changes")
                .isEqualTo(moneyFacts);
    }

    // ---- 7. day 1: closings, operational and financial close, reopen, adjust, round 1 ----

    private void closeDayOneReopenAdjustAndApproveRoundOne() throws Exception {
        UUID closing1 = submittedClosing(manager, register1, "closing register 1", "Pao comprado da gaveta");
        UUID closing2 = submittedClosing(manager, register2, "closing register 2", null);
        office.command(BO + "/cash/closings/" + closing1 + "/approve", null, 200, "approve closing 1");
        office.command(BO + "/cash/closings/" + closing2 + "/approve", null, 200, "approve closing 2");
        operationalClose("close day 1 operationally");
        assertThat(office.command(BO + "/cash/days/" + cashDay1 + "/financial-close", null, 200,
                "close day 1 financially").get("status").asString()).isEqualTo("FINANCIALLY_CLOSED");

        Map<String, String> approval = approver();
        JsonNode reopened = office.post(BO + "/cash/days/" + cashDay1 + "/reopen", Map.of("reason",
                "Ajuste de pao em falta"), 200, Csrf.SEND, approval, "reopen day 1 financially");
        assertThat(reopened.get("status").asString()).isEqualTo("OPEN");
        office.command(BO + "/cash/sessions/" + session1 + "/adjustments", Map.of("direction", "OUT",
                "methodCode", "CASH", "amount", new BigDecimal("5.00"), "reason", "Pao pago em dinheiro"), 201,
                "adjust session 1");
        operationalClose("close day 1 operationally again");
        JsonNode roundOne = office.command(BO + "/cash/closings/" + submittedClosing(manager, register1,
                "closing register 1, round 1", null) + "/approve", null, 200, "approve round 1");
        assertThat(roundOne.get("dayRound").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM cash.cash_closing WHERE id = ? AND status = 'APPROVED' "
                + "AND day_round = 0", closing1)).as("the round-0 closing is kept").isEqualTo(1);
        office.command(BO + "/cash/days/" + cashDay1 + "/financial-close", null, 200, "close day 1 financially again");
    }

    // ---- 8. reports ----

    private void reportsEqualTheFacts() throws Exception {
        String range = "?storeId=" + storeA + "&from=" + day1 + "&to=" + day1;
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .until(() -> office.get(BO + "/reports/sales" + range + "&groupBy=item", 200).get("final").asBoolean());
        JsonNode byItem = office.get(BO + "/reports/sales" + range + "&groupBy=item", 200);
        assertThat(byItem.get("pendingFacts").asLong()).isZero();
        assertThat(money(byItem.get("totals").get("gross"))).isEqualByComparingTo(decimal(
                "SELECT sum(total_gross) FROM fiscal.fiscal_document WHERE store_id = ? AND status = 'ISSUED' "
                        + "AND business_date = ?", storeA, day1));
        Map<String, BigDecimal> itemGross = new TreeMap<>();
        byItem.get("rows").forEach(r -> itemGross.put(r.get("label").asString(), money(r.get("gross"))));
        assertThat(itemGross).isEqualTo(documentGrossPerDescription(day1));
        for (String groupBy : List.of("hour", "employee", "area")) {
            JsonNode rows = office.get(BO + "/reports/sales" + range + "&groupBy=" + groupBy, 200);
            assertThat(money(rows.get("totals").get("gross"))).as("sales by %s", groupBy)
                    .isEqualByComparingTo(money(byItem.get("totals").get("gross")));
        }
        JsonNode taxes = office.get(BO + "/reports/taxes" + range, 200);
        Map<String, BigDecimal> reportedTax = new TreeMap<>();
        taxes.get("rows").forEach(r -> reportedTax.merge(new BigDecimal(r.get("rate").asString()).setScale(2)
                .toPlainString(), money(r.get("tax")), BigDecimal::add));
        assertThat(reportedTax).isEqualTo(documentTaxPerRateOfDay(day1));
        JsonNode receipts = office.get(BO + "/reports/receipts" + range + "&groupBy=method", 200);
        Map<String, BigDecimal> received = new TreeMap<>();
        receipts.get("rows").forEach(r -> received.put(r.get("key").asString(), money(r.get("received"))));
        assertThat(received).isEqualTo(receiptsPerMethod(day1));
        assertThat(money(receipts.get("totals").get("onAccount"))).as("credit is separate, not received")
                .isEqualByComparingTo("150.00");

        // a rebuild from empty gives identical results
        List<JsonNode> live = reportsOf(range);
        JsonNode rebuild = office.command(BO + "/reports/rebuild", Map.of("storeId", storeA, "from", day1.toString(),
                "to", day1.toString()), 202, "rebuild day 1");
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() ->
                office.get(BO + "/reports/status?storeId=" + storeA, 200).get("lastRebuild").get("status").asString(),
                s -> s.equals("COMPLETED") || s.equals("FAILED"));
        assertThat(office.get(BO + "/reports/status?storeId=" + storeA, 200).get("lastRebuild").get("id").asString())
                .isEqualTo(id(rebuild).toString());
        assertThat(office.get(BO + "/reports/status?storeId=" + storeA, 200).get("lastRebuild").get("status")
                .asString()).isEqualTo("COMPLETED");
        assertThat(reportsOf(range)).isEqualTo(live);
        String export = office.postForText(BO + "/reports/exports", Map.of("report", "taxes", "params", Map.of(
                "storeId", storeA.toString(), "from", day1.toString(), "to", day1.toString())), 200, "export taxes");
        assertThat(export).contains("23");
    }

    // ---- 8. audit and support access ----

    private void auditAndSupportAccess() throws Exception {
        Instant from = clock.instant().minus(Duration.ofDays(2));
        Instant to = clock.instant().plus(Duration.ofMinutes(1));
        String window = "&from=" + from + "&to=" + to;
        JsonNode events = office.get(BO + "/audit/events?storeId=" + storeA + "&action=ORDER_CLOSED" + window, 200);
        assertThat(events.get("content")).hasSize(3);
        office.get(BO + "/audit/events?storeId=" + storeA + "&from=" + from.minus(Duration.ofDays(92)) + "&to=" + to,
                422);
        // a store-B audit row, then a store-A auditor and a store-A waiter on the BackOffice
        office.post(BO + "/areas", Map.of("storeId", storeB, "code", "SALAB", "name", "Sala B"), 201);
        Browser auditor = backOfficeUser("auditor", "AUD1", AUDITOR_ROLE);
        Browser waiter = backOfficeUser("waiter", "EMP1", WAITER_ROLE);
        waiter.get(BO + "/audit/events?storeId=" + storeA + window, 403);
        auditor.get(BO + "/audit/events?storeId=" + storeB + window, 403);
        JsonNode own = auditor.get(BO + "/audit/events?size=200" + window, 200);
        assertThat(own.get("content")).isNotEmpty().allSatisfy(e -> {
            if (e.hasNonNull("storeId")) {
                assertThat(e.get("storeId").asString()).isEqualTo(storeA.toString());
            }
        });
        String csv = auditor.postForText(BO + "/audit/exports", Map.of("storeId", storeA.toString(), "from",
                from.toString(), "to", to.toString(), "action", "ORDER_CLOSED"), 200, "audit export");
        assertThat(csv.lines().count()).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'AUDIT_EXPORTED'")).isEqualTo(1);

        // A34: a support account logs in only under a grant; after 2 h its session is revoked (401)
        String username = "suporte.r3";
        UUID account = id(office.post(BO + "/users", Map.of("username", username, "initialPassword",
                SUPPORT_PASSWORD, "roleIds", List.of(), "support", true), 201));
        Browser support = office.sibling("support");
        support.get("/api/v1/auth/session", 401);
        support.post("/api/v1/auth/login", Map.of("username", username, "password", SUPPORT_PASSWORD), 401);
        UUID grant = id(office.command(BO + "/support-grants", Map.of("accountId", account, "durationMinutes", 120,
                "reason", "Suporte ao fecho do dia"), 201, "support grant 2 h"));
        support.post("/api/v1/auth/login", Map.of("username", username, "password", SUPPORT_PASSWORD), 200);
        support.get("/api/v1/auth/session", 200);
        clock.advance(Duration.ofMinutes(121));
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250))
                .until(() -> support.send("GET", "/api/v1/auth/session", null, Csrf.OMIT, Map.of()).statusCode()
                        == 401);
        support.post("/api/v1/auth/login", Map.of("username", username, "password", SUPPORT_PASSWORD), 401);
        // every step is audited: the grant, the login under it, and the expiry by the system
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250))
                .until(() -> audits("SUPPORT_GRANT_EXPIRED", grant) == 1);
        assertThat(audits("SUPPORT_GRANT_CREATED", grant)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'SUPPORT_GRANT_EXPIRED' "
                + "AND entity_id = ? AND actor_kind = 'SYSTEM'", grant.toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'USER_LOGGED_IN' AND entity_id = ? "
                + "AND details ->> 'supportGrantId' = ?", account.toString(), grant.toString())).isEqualTo(1);
    }

    // ---- helpers ----

    private Browser backOfficeUser(String name, String code, UUID role) throws Exception {
        UUID employeeId = id(office.post(BO + "/employees", Map.of("code", code, "name", name, "pin", "9182",
                "storeId", storeA, "roleId", role), 201));
        String username = name + ".r3";
        String password = "pw " + UUID.randomUUID();
        office.post(BO + "/users", Map.of("username", username, "initialPassword", password, "employeeId",
                employeeId), 201);
        Browser tab = office.sibling(name);
        tab.get("/api/v1/auth/session", 401);
        tab.post("/api/v1/auth/login", Map.of("username", username, "password", password), 200);
        tab.post("/api/v1/auth/password", Map.of("currentPassword", password, "newPassword", password + " 2"), 200);
        return tab;
    }

    /** Shifts the application clock past store A's next cutoff (the store was created with it 12 h away). */
    private void moveToNextBusinessDay() throws Exception {
        JsonNode store = office.get(BO + "/stores/" + storeA, 200);
        LocalTime cutoff = LocalTime.parse(store.get("businessDayCutoff").asString());
        ZoneId zone = ZoneId.of(store.get("timezone").asString());
        ZonedDateTime now = clock.instant().atZone(zone);
        ZonedDateTime next = now.with(cutoff);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        clock.moveTo(next.plusMinutes(30).toInstant());
    }

    private UUID submittedClosing(Browser pos, UUID register, String step, String cashJustification)
            throws Exception {
        JsonNode draft = pos.command(POS + "/cash/closings", Map.of("registerId", register, "cashDayId", cashDay1),
                201, step);
        UUID closing = id(draft);
        if (cashJustification == null) {
            draft.get("lines").forEach(l -> assertThat(money(l.get("difference"))).as("difference of %s", l)
                    .isEqualByComparingTo("0"));
        } else {
            pos.put(POS + "/cash/closings/" + closing, Map.of("version", draft.get("version").asLong(),
                    "justifications", List.of(Map.of("methodCode", "CASH", "text", cashJustification))), 200,
                    "justify " + step);
        }
        pos.command(POS + "/cash/closings/" + closing + "/submit", null, 200, "submit " + step);
        return closing;
    }

    private void operationalClose(String step) throws Exception {
        long version = jdbc.sql("SELECT version FROM cash.cash_day WHERE id = ?").param(cashDay1).query(Long.class)
                .single();
        manager.command(POS + "/cash/days/" + cashDay1 + "/operational-close", Map.of("version", version), 200, step);
    }

    private static List<Map<String, Object>> expectedCounts(JsonNode session) {
        List<Map<String, Object>> counts = new ArrayList<>();
        session.get("expected").forEach(e -> counts.add(counted(e.get("methodCode").asString(),
                money(e.get("amount")).toPlainString())));
        return counts;
    }

    private static Map<String, Object> counted(String method, String amount) {
        return Map.of("methodCode", method, "amount", new BigDecimal(amount));
    }

    private List<JsonNode> reportsOf(String range) throws Exception {
        List<JsonNode> reports = new ArrayList<>();
        for (String path : List.of("/reports/sales" + range + "&groupBy=item", "/reports/sales" + range
                + "&groupBy=hour", "/reports/taxes" + range, "/reports/receipts" + range + "&groupBy=method",
                "/reports/voids" + range + "&groupBy=reason")) {
            JsonNode report = office.get(BO + path, 200);
            ((tools.jackson.databind.node.ObjectNode) report).remove("asOf");
            reports.add(report);
        }
        return reports;
    }

    private Map<String, String> approver() {
        Map<String, String> headers = new LinkedHashMap<>(Browser.idem());
        headers.put("X-Approver-Code", managerCode);
        headers.put("X-Approver-Pin", MANAGER_PIN);
        return headers;
    }

    private void method(String code, String name, String kind, String providerCode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("kind", kind);
        body.put("providerCode", providerCode);
        office.command(BO + "/payment-methods", body, 201, "method " + code);
    }

    private UUID table(String code) throws Exception {
        return id(office.post(BO + "/tables", Map.of("storeId", storeA, "areaId", area, "code", code, "seats", 4),
                201));
    }

    private static Map<String, Object> attempt(UUID order, UUID partition, String method, String amount,
                                               String tendered) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", order);
        body.put("partitionId", partition);
        body.put("methodCode", method);
        body.put("amount", new BigDecimal(amount));
        body.put("tendered", tendered == null ? null : new BigDecimal(tendered));
        return body;
    }

    private static Map<String, Object> lineQty(UUID line, String quantity) {
        return Map.of("lineId", line, "quantity", new BigDecimal(quantity));
    }

    private UUID line(Browser pos, UUID order, String item, String quantity) throws Exception {
        return id(pos.command(POS + "/orders/" + order + "/lines", Map.of("itemId", items.get(item),
                "quantity", new BigDecimal(quantity)), 201, "draft " + item));
    }

    private void confirm(Browser pos, UUID order, List<UUID> lines, String step) throws Exception {
        pos.command(POS + "/orders/" + order + "/batches", Map.of("orderVersion", version(pos, order),
                "catalogVersion", catalogVersion, "lineIds", lines), 201, step);
    }

    private long version(Browser pos, UUID order) throws Exception {
        return pos.get(POS + "/orders/" + order, 200).get("version").asLong();
    }

    private JsonNode settlement(Browser pos, UUID order) throws Exception {
        return pos.get(POS + "/orders/" + order + "/settlement", 200);
    }

    private String attemptStatus(Browser pos, UUID attempt) throws Exception {
        return pos.get(POS + "/payment-attempts/" + attempt, 200).get("status").asString();
    }

    private UUID paymentOf(UUID attempt) {
        return jdbc.sql("SELECT id FROM settlement.payment WHERE attempt_id = ?").param(attempt).query(UUID.class)
                .single();
    }

    private UUID printer(UUID store, String code, TcpFakePrinter device, String codePage) throws Exception {
        return id(office.command(BO + "/printers", Map.of("storeId", store, "code", code, "name", "Impressora " + code,
                "host", device.host(), "port", device.port(), "codePage", codePage), 201, "printer " + code));
    }

    private static TcpFakePrinter open() {
        try {
            return TcpFakePrinter.accepting();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The one payload the printer received that contains {@code marker} and matches {@code also}. */
    private static String awaitPrinted(TcpFakePrinter device, Charset charset, String marker, Predicate<String> also)
            throws InterruptedException {
        Predicate<String> match = s -> s.contains(marker) && also.test(s);
        List<String> printed = device.awaitPayloads(charset, match, 1, PRINTED);
        assertThat(printed).as("printed on port %d: %s", device.port(), marker).hasSize(1);
        return printed.getFirst();
    }

    private static String printed(TcpFakePrinter device, Charset charset, String marker) throws InterruptedException {
        return awaitPrinted(device, charset, marker, s -> true);
    }

    private static List<String> kinds(JsonNode exceptions) {
        return StreamSupport.stream(exceptions.spliterator(), false).map(e -> e.get("kind").asString()).toList();
    }

    private Map<String, BigDecimal> documentTaxPerRate(UUID order) {
        return perKey("""
                SELECT to_char(t.tax_rate, 'FM990.00') AS k, sum(t.tax) AS v FROM fiscal.fiscal_document_tax t
                JOIN fiscal.fiscal_document d ON d.id = t.document_id
                WHERE d.order_id = ? AND d.status = 'ISSUED' GROUP BY 1""", order);
    }

    private Map<String, BigDecimal> documentTaxPerRateOfDay(LocalDate day) {
        return perKey("""
                SELECT to_char(t.tax_rate, 'FM990.00') AS k, sum(t.tax) AS v FROM fiscal.fiscal_document_tax t
                JOIN fiscal.fiscal_document d ON d.id = t.document_id
                WHERE d.store_id = ? AND d.business_date = ? AND d.status = 'ISSUED' GROUP BY 1""", storeA, day);
    }

    /** The order's tax per rate: Σ of its lines' tax recomputed on the net quantity (D96). */
    private Map<String, BigDecimal> orderTaxPerRate(UUID order) {
        return perKey("SELECT to_char(n.rate, 'FM990.00') AS k, sum(round(n.gross - n.gross / (1 + n.rate / 100), 2))"
                + " AS v FROM (" + NET_LINES + ") n GROUP BY 1", order);
    }

    /** The order's confirmed lines with a net quantity left after their voids: id, rate, net, gross. */
    private static final String NET_LINES = """
            SELECT q.id, q.rate, q.net, q.unit_price * q.net AS gross FROM (
                SELECT l.id, l.tax_rate AS rate, l.unit_price,
                       l.quantity - coalesce((SELECT sum(v.quantity) FROM orders.order_line_void v
                                              WHERE v.line_id = l.id), 0) AS net
                FROM orders.order_line l WHERE l.order_id = ? AND l.batch_id IS NOT NULL) q
            WHERE q.net > 0""";

    private Map<String, BigDecimal> documentQuantityPerLine(UUID order) {
        return perKey("""
                SELECT l.source_line_id::text AS k, sum(l.quantity) AS v FROM fiscal.fiscal_document_line l
                JOIN fiscal.fiscal_document d ON d.id = l.document_id
                WHERE d.order_id = ? AND d.status = 'ISSUED' GROUP BY 1""", order);
    }

    private Map<String, BigDecimal> netQuantityPerLine(UUID order) {
        return perKey("SELECT n.id::text AS k, n.net AS v FROM (" + NET_LINES + ") n", order);
    }

    private Map<String, BigDecimal> documentGrossPerDescription(LocalDate day) {
        return perKey("""
                SELECT l.description AS k, sum(l.gross) AS v FROM fiscal.fiscal_document_line l
                JOIN fiscal.fiscal_document d ON d.id = l.document_id
                WHERE d.store_id = ? AND d.business_date = ? AND d.status = 'ISSUED' GROUP BY 1""", storeA, day);
    }

    /** Σ (PAYMENT_RECEIPT − REFUND) cash movements per method of store A's business day. */
    private Map<String, BigDecimal> receiptsPerMethod(LocalDate day) {
        return perKey("""
                SELECT m.method_code AS k,
                       sum(CASE WHEN m.type = 'PAYMENT_RECEIPT' THEN m.amount ELSE -m.amount END) AS v
                FROM cash.cash_movement m JOIN cash.cash_day d ON d.id = m.cash_day_id
                WHERE d.store_id = ? AND d.business_date = ? AND m.type IN ('PAYMENT_RECEIPT', 'REFUND')
                GROUP BY 1""", storeA, day);
    }

    private Map<String, BigDecimal> perKey(String sql, Object... params) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        for (Object param : params) {
            statement = statement.param(param);
        }
        Map<String, BigDecimal> rows = new TreeMap<>();
        statement.query((rs, n) -> Map.entry(rs.getString("k"), rs.getBigDecimal("v").setScale(2,
                java.math.RoundingMode.UNNECESSARY))).list().forEach(e -> rows.put(e.getKey(), e.getValue()));
        return rows;
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

    private BigDecimal decimal(String sql, Object... params) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        for (Object param : params) {
            statement = statement.param(param);
        }
        return statement.query(BigDecimal.class).single();
    }

    private static BigDecimal money(JsonNode node) {
        return new BigDecimal(node.get("amount").asString());
    }
}
