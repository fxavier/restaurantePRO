package com.xavier.backend.e2e;

import static com.xavier.backend.e2e.Installation.CASHIER_ROLE;
import static com.xavier.backend.e2e.Installation.MANAGER_ROLE;
import static com.xavier.backend.e2e.Installation.employee;
import static com.xavier.backend.e2e.Installation.id;
import static com.xavier.backend.e2e.Installation.posLogin;
import static com.xavier.backend.e2e.Installation.terminal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.xavier.backend.BackendApplication;
import com.xavier.backend.e2e.Browser.Csrf;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.orders.OrdersProductionApi;
import com.xavier.backend.production.TcpFakePrinter;
import com.xavier.backend.reporting.service.Projections;
import com.xavier.backend.settlement.FakePaymentProvider;
import com.xavier.backend.settlement.SettlementTestClock;
import com.xavier.backend.settlement.service.CreditSaleSteps;
import com.xavier.backend.settlement.service.SettlementIssuer;
import com.xavier.backend.shared.events.EventPublicationRecovery;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The restart matrix (D90 a–f, plus (g) and (h) for D96 and D97; A29, spec §14 #5 and #3) at system level: real HTTP,
 * real sessions in PostgreSQL, the production print worker and ESC/POS over TCP. Every crash point ends with exactly one
 * logical effect.
 *
 * <p>Instance A is this test's server. It is the one that "crashes": a spy makes the step after the crash point throw
 * (a, b, f) or never return (c, g, h: the request thread stays parked, holding no transaction, as a dead process would
 * leave it), and its own recovery jobs and print worker are switched off (PT1H), so nothing it does after the crash can
 * recover anything. Instance B is a second, independent application started on the same (fresh) database, with the
 * recovery jobs on and its clock two minutes ahead (past the sweeper grace and the in-doubt poll age; nothing sleeps).
 * The POS client moves to B with its cookies (the session lives in PostgreSQL) and retries with the same
 * Idempotency-Key, as a tab does after "server unavailable". The integrated provider is one instance shared by A and B:
 * it is outside both, and remembers what it really did.
 * <ol>
 *   <li>(a) a round committed, production not finished: B republishes it → one ticket, one job, printed once;</li>
 *   <li>(b) a credit document issued, the debit not posted: B's sweeper posts exactly one debit; the retry adds nothing;</li>
 *   <li>(c) a partition document issued, the response never sent: B's retry takes the expired claim over → the same
 *       document, one number;</li>
 *   <li>(d) a print send cut mid-way (PROCESSING past its lease): B marks it UNKNOWN and never resends it by itself;</li>
 *   <li>(e) an integrated charge left UNKNOWN: B's poller resolves it into exactly one Payment, one charge;</li>
 *   <li>(f) a reporting listener that fails twice is completed by the runtime resubmission job, without a restart;</li>
 *   <li>(g) a residual draft with a D96 placement, not issued: B's retried close issues that same draft, with the same
 *       adjustment row and one number;</li>
 *   <li>(h) a D97 snapshot refreshed, not issued: a payment lands meanwhile; B's retry refreshes again and issues with
 *       the fresh snapshot and one number.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "restaurantpro.settlement.provider-timeout=PT1S",
        "restaurantpro.settlement.sweep-interval=PT1H",
        "restaurantpro.settlement.credit-sweep-interval=PT1H",
        "restaurantpro.settlement.in-doubt-poll-interval=PT1H",
        "restaurantpro.production.worker.poll=PT1H",
        "restaurantpro.events.resubmit-interval=PT1H",
        "restaurantpro.idempotency.step-lease=PT3S"})
@Import({Installation.FreshDatabase.class, FakePaymentProvider.Config.class, SettlementTestClock.Config.class})
@MockitoSpyBean(types = {OrdersProductionApi.class, CreditSaleSteps.class, FiscalApi.class, SettlementIssuer.class,
        Projections.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RestartMatrixTest {

    static final String INITIAL_PASSWORD = "initial " + UUID.randomUUID();
    static final String NEW_PASSWORD = "changed " + UUID.randomUUID();
    static final String CASHIER_PIN = "5172";
    static final String MANAGER_PIN = "6283";
    static final String COMPANY = "Restaurante Reinicio";
    static final Charset CP860 = Charset.forName("IBM860");
    static final Charset WPC1252 = Charset.forName("windows-1252");
    static final String POS = "/api/v1/pos";
    static final String BO = "/api/v1/backoffice";
    static final Duration RECOVERED = Duration.ofSeconds(30);

    static final TcpFakePrinter KITCHEN = open();
    static final TcpFakePrinter BAR = open();
    static final TcpFakePrinter COUNTER = open();

    /** Never counted down while B runs: the crashed requests of A stay parked; released (to throw) at the end. */
    static final CountDownLatch DEAD = new CountDownLatch(1);

    @DynamicPropertySource
    static void bootstrapSettings(DynamicPropertyRegistry registry) throws IOException {
        Installation.bootstrap(registry, COMPANY, INITIAL_PASSWORD);
    }

    @AfterAll
    static void unplug() throws IOException {
        DEAD.countDown();
        KITCHEN.close();
        BAR.close();
        COUNTER.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer freshPostgres;

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    SettlementTestClock clock;

    @Autowired
    OrdersProductionApi ordersProduction;

    @Autowired
    CreditSaleSteps creditSteps;

    @Autowired
    FiscalApi fiscal;

    @Autowired
    SettlementIssuer issuer;

    @Autowired
    Projections projections;

    @Autowired
    EventPublicationRecovery recovery;

    private final List<String> journey = new ArrayList<>();

    Browser office;
    Browser cashier;
    UUID store;
    UUID area;
    UUID customer;
    String managerCode;
    Map<String, UUID> items;
    long catalogVersion;

    @Test
    void everyCrashPointEndsWithExactlyOneLogicalEffect() throws Exception {
        try {
            setUp();
            // ---- phase 1, instance A: each case stops at its crash point ----
            Crash a = roundCommittedProductionNotFinished();
            Crash b = creditDocumentIssuedDebitNotPosted();
            Crash c = partitionDocumentIssuedResponseNeverSent();
            Crash d = printSendCutMidway();
            Crash e = integratedChargeLeftUnknown();
            Crash g = residualDraftPlacedNotIssued();
            Crash h = snapshotRefreshedNotIssued();

            // ---- phase 2, instance B: a second application on the same database ----
            try (ConfigurableApplicationContext restarted = restart()) {
                restarted.getBean(SettlementTestClock.class).advance(Duration.ofMinutes(2));
                Browser pos = cashier.movedTo(((WebServerApplicationContext) restarted).getWebServer().getPort());
                Browser bo = office.movedTo(pos.port());
                recoveredA(a);
                recoveredB(b, pos);
                recoveredC(c, pos);
                recoveredE(e);
                recoveredG(g, pos);
                recoveredH(h, pos);
                recoveredD(d, bo, c, g, h); // last: B has printed the later documents by then
            }

            // ---- (f), instance A: no restart, the runtime resubmission ----
            listenerFailingTwiceIsCompletedByTheJob();
        } finally {
            clock.reset();
            System.out.println("Restart matrix (step -> HTTP status):\n  " + String.join("\n  ", journey));
        }
    }

    /** What a case left behind for its recovery check. */
    record Crash(UUID order, UUID subject, String key, Map<String, Object> body, String path, Object detail) {
    }

    // ---- setup ----

    private void setUp() throws Exception {
        office = new Browser("backoffice", port, journey);
        Installation.firstLogin(office, INITIAL_PASSWORD, NEW_PASSWORD);
        store = Installation.store(office, "R");
        UUID terminal = terminal(office, store, "POS1");
        String cashierCode = employee(office, store, "CX1", "Carla", CASHIER_PIN, CASHIER_ROLE);
        managerCode = employee(office, store, "GR1", "Gil", MANAGER_PIN, MANAGER_ROLE);
        area = id(office.post(BO + "/areas", Map.of("storeId", store, "code", "SALA", "name", "Sala"), 201));
        items = Installation.publishTwoRateCatalog(office, store);
        UUID kitchen = printer("COZ", KITCHEN, "CP860");
        UUID bar = printer("BAR", BAR, "CP860");
        UUID counter = printer("BALCAO", COUNTER, "WPC1252");
        office.put(BO + "/production-routes", Map.of("storeId", store, "routes", List.of(
                Map.of("destinationCode", "COZINHA", "printerId", kitchen),
                Map.of("destinationCode", "BAR", "printerId", bar))), 200, "routes");
        office.put(BO + "/terminal-printers", Map.of("storeId", store, "assignments",
                List.of(Map.of("terminalId", terminal, "printerId", counter))), 200, "terminal printer");
        UUID register = id(office.command(BO + "/cash/registers", Map.of("storeId", store, "code", "CX1",
                "name", "Caixa 1"), 201, "register"));
        office.command(BO + "/fiscal/series", Map.of("storeId", store, "code", "FR", "firstNumber", 1), 201,
                "series");
        Map<String, Object> mpesa = new LinkedHashMap<>();
        mpesa.put("code", "MPESA");
        mpesa.put("name", "M-Pesa");
        mpesa.put("kind", "INTEGRATED");
        mpesa.put("providerCode", FakePaymentProvider.CODE);
        office.command(BO + "/payment-methods", mpesa, 201, "integrated method");
        customer = id(office.command(BO + "/customers", Map.of("name", "Rui Tembe", "phone", "842345678"), 201,
                "customer"));
        office.command(BO + "/customers/" + customer + "/credit-limit-changes", Map.of("newLimit",
                new BigDecimal("500.00"), "reason", "Cliente habitual"), 200, "credit limit");
        cashier = posLogin(office, terminal, cashierCode, CASHIER_PIN, "cashier");
        cashier.command(POS + "/cash/sessions", Map.of("registerId", register, "openingFloat",
                new BigDecimal("0.00")), 201, "open session");
        catalogVersion = cashier.get(POS + "/catalog?storeId=" + store, 200).get("version").asLong();
    }

    // ---- (a) ----

    private Crash roundCommittedProductionNotFinished() throws Exception {
        UUID order = order("Ma");
        UUID sumo = line(order, "SUMO", "1");
        OrdersProductionApi spy = AopTestUtils.getUltimateTargetObject(ordersProduction);
        doThrow(new IllegalStateException("crash before production finished")).when(spy).markBatchDispatched(any());
        UUID batch;
        try {
            batch = id(cashier.command(POS + "/orders/" + order + "/batches", Map.of("orderVersion", version(order),
                    "catalogVersion", catalogVersion, "lineIds", List.of(sumo)), 201, "(a) confirm the round"));
            Awaitility.await().atMost(RECOVERED).until(() -> incompleteProduction(batch));
        } finally {
            reset(spy);
        }
        assertThat(count("SELECT count(*) FROM production.production_ticket WHERE source_id = ?", batch)).isZero();
        return new Crash(order, batch, null, null, null, null);
    }

    private void recoveredA(Crash a) throws Exception {
        UUID batch = a.subject();
        Awaitility.await().atMost(RECOVERED).until(() -> !incompleteProduction(batch));
        Awaitility.await().atMost(RECOVERED).until(() -> count("SELECT count(*) FROM production.print_job j "
                + "JOIN production.production_ticket t ON t.id = j.ticket_id WHERE t.source_id = ? "
                + "AND j.status = 'SENT'", batch) == 1);
        assertThat(count("SELECT count(*) FROM production.production_ticket WHERE source_id = ?", batch)).isEqualTo(1);
        assertThat(BAR.awaitPayloads(CP860, s -> s.contains("Mesa Ma"), 1, RECOVERED)).hasSize(1);
        journey.add("(a) republished at B's start: 1 ticket, 1 job, printed once");
    }

    // ---- (b) ----

    private Crash creditDocumentIssuedDebitNotPosted() throws Exception {
        UUID order = order("Mb");
        confirm(order, List.of(line(order, "PREGO", "1")), "(b) round");
        CreditSaleSteps spy = AopTestUtils.getUltimateTargetObject(creditSteps);
        doThrow(new IllegalStateException("crash before the debit")).when(spy).post(any(), any());
        Map<String, String> key = Browser.idem();
        Map<String, Object> body = Map.of("customerId", customer, "amount", new BigDecimal("150.00"));
        try {
            cashier.post(POS + "/orders/" + order + "/credit-charges", body, 500, Csrf.SEND, key,
                    "(b) credit sale, crash after the issue");
        } finally {
            reset(spy);
        }
        assertThat(chargeStatus(order)).isEqualTo("RESERVED");
        assertThat(count("SELECT count(*) FROM fiscal.fiscal_document WHERE order_id = ? AND status = 'ISSUED'",
                order)).isEqualTo(1);
        return new Crash(order, null, key.get("Idempotency-Key"), body, POS + "/orders/" + order + "/credit-charges",
                null);
    }

    private void recoveredB(Crash b, Browser pos) throws Exception {
        Awaitility.await().atMost(RECOVERED).until(() -> "POSTED".equals(chargeStatus(b.order())));
        UUID charge = jdbc.sql("SELECT id FROM settlement.credit_charge WHERE order_id = ?").param(b.order())
                .query(UUID.class).single();
        assertThat(count("SELECT count(*) FROM customers.credit_ledger_entry WHERE source_id = ?", charge))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'CREDIT_CHARGE_POSTED' "
                + "AND entity_id = ? AND actor_kind = 'SYSTEM'", charge.toString())).isEqualTo(1);
        JsonNode retried = pos.post(b.path(), b.body(), 201, Csrf.SEND, Map.of("Idempotency-Key", b.key()),
                "(b) the client's retry at B");
        assertThat(retried.get("status").asString()).isEqualTo("POSTED");
        assertThat(count("SELECT count(*) FROM customers.credit_ledger_entry WHERE source_id = ?", charge))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM fiscal.fiscal_document WHERE order_id = ? AND number IS NOT NULL",
                b.order())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM cash.cash_movement m JOIN fiscal.fiscal_document d "
                + "ON d.id = m.source_id WHERE d.order_id = ?", b.order())).as("credit is no cash").isZero();
        journey.add("(b) B's sweeper posted 1 debit as SYSTEM; the retry answered POSTED, nothing added");
    }

    // ---- (c) ----

    private Crash partitionDocumentIssuedResponseNeverSent() throws Exception {
        UUID order = order("Mc");
        UUID prego = line(order, "PREGO", "1");
        UUID sumo = line(order, "SUMO", "1");
        confirm(order, List.of(prego, sumo), "(c) round");
        UUID partition = partition(order, sumo, "1", "(c) partition");
        pay(order, partition, "60.00", "(c) pay the partition");
        FiscalApi spy = AopTestUtils.getUltimateTargetObject(fiscal);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            parkAsDead();
            return null;
        }).when(spy).issue(any(), any());
        String key = UUID.randomUUID().toString();
        String path = POS + "/partitions/" + partition + "/documents";
        sendAndForget(path, Map.of(), key);
        Awaitility.await().atMost(RECOVERED).until(() -> count("SELECT count(*) FROM fiscal.fiscal_document "
                + "WHERE source_id = ? AND status = 'ISSUED'", partition) == 1);
        reset(spy);
        UUID document = jdbc.sql("SELECT id FROM fiscal.fiscal_document WHERE source_id = ?").param(partition)
                .query(UUID.class).single();
        return new Crash(order, document, key, Map.of(), path, partition);
    }

    private void recoveredC(Crash c, Browser pos) throws Exception {
        JsonNode retried = pos.post(c.path(), c.body(), 201, Csrf.SEND, Map.of("Idempotency-Key", c.key()),
                "(c) retry the partition document at B");
        assertThat(retried.get("id").asString()).isEqualTo(c.subject().toString());
        assertThat(count("SELECT count(*) FROM fiscal.fiscal_document WHERE source_id = ?", c.detail()))
                .isEqualTo(1);
        assertThat(pos.post(c.path(), c.body(), 201, Csrf.SEND, Map.of("Idempotency-Key", c.key()),
                "(c) and again").get("id").asString()).isEqualTo(c.subject().toString());
        journey.add("(c) B took the expired claim over: the same document " + retried.get("number").asLong());
    }

    // ---- (d) ----

    private Crash printSendCutMidway() throws Exception {
        UUID order = order("Md");
        confirm(order, List.of(line(order, "AGUA", "1")), "(d) round");
        cashier.post(POS + "/orders/" + order + "/subtotal/print", null, 202, Csrf.SEND, Browser.idem(),
                "(d) subtotal print");
        String subtotalJob = """
                SELECT j.id FROM production.print_job j JOIN production.production_ticket t ON t.id = j.ticket_id
                WHERE t.order_id = ? AND t.source_type = 'SUBTOTAL'""";
        Awaitility.await().atMost(RECOVERED).until(() -> jdbc.sql(subtotalJob).param(order).query(UUID.class)
                .optional().isPresent());
        UUID job = jdbc.sql(subtotalJob).param(order).query(UUID.class).single();
        // as a crash mid-send leaves it: claimed (PROCESSING, attempt 1) and its lease long gone
        assertThat(jdbc.sql("""
                        UPDATE production.print_job SET status = 'PROCESSING', attempts = 1,
                               started_at = now() - interval '5 minutes' WHERE id = ? AND status = 'QUEUED'""")
                .param(job).update()).as("A's worker is off: the job was still queued").isOne();
        return new Crash(order, job, null, null, null, null);
    }

    private void recoveredD(Crash d, Browser bo, Crash... later) throws Exception {
        Awaitility.await().atMost(RECOVERED).until(() -> "UNKNOWN".equals(jdbc.sql(
                "SELECT status FROM production.print_job WHERE id = ?").param(d.subject()).query(String.class)
                .single()));
        // positive control: B has printed the documents of (c), (g) and (h), all queued after the subtotal, on the
        // same printer; the subtotal never reached it and no job was added for it
        for (Crash c : later) {
            Awaitility.await().atMost(RECOVERED).until(() -> count("""
                    SELECT count(*) FROM production.print_job j JOIN production.production_ticket t ON t.id = j.ticket_id
                    JOIN fiscal.fiscal_document d ON d.id = t.source_id
                    WHERE d.order_id = ? AND t.source_type = 'DOCUMENT' AND j.status = 'SENT'""", c.order()) >= 1);
        }
        assertThat(COUNTER.matching(WPC1252, s -> s.contains("SUBTOTAL") && s.contains("Mesa Md"))).isEmpty();
        assertThat(count("SELECT count(*) FROM production.print_job j JOIN production.production_ticket t "
                + "ON t.id = j.ticket_id WHERE t.order_id = ? AND t.source_type = 'SUBTOTAL'", d.order())).isEqualTo(1);
        journey.add("(d) the cut send is UNKNOWN for a person, never resent; later jobs printed");
    }

    // ---- (e) ----

    private Crash integratedChargeLeftUnknown() throws Exception {
        UUID order = order("Me");
        confirm(order, List.of(line(order, "BOLO", "1")), "(e) round");
        provider.willNext(FakePaymentProvider.Behaviour.HANG);
        UUID attempt = id(cashier.command(POS + "/payment-attempts", attempt(order, null, "MPESA", "45.00", null), 202,
                "(e) integrated charge (times out)"));
        Awaitility.await().atMost(RECOVERED).until(() -> "UNKNOWN".equals(attemptStatus(attempt)));
        provider.willNext(FakePaymentProvider.Behaviour.SUCCEED);
        return new Crash(order, attempt, null, null, null, null);
    }

    private void recoveredE(Crash e) {
        Awaitility.await().atMost(RECOVERED).until(() -> "SUCCEEDED".equals(attemptStatus(e.subject())));
        assertThat(count("SELECT count(*) FROM settlement.payment WHERE attempt_id = ?", e.subject())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM cash.cash_movement m JOIN settlement.payment p ON p.id = m.source_id "
                + "WHERE p.attempt_id = ?", e.subject())).isEqualTo(1);
        assertThat(provider.charges(e.subject().toString())).isEqualTo(1);
        journey.add("(e) B's poller resolved the UNKNOWN attempt: 1 Payment, 1 receipt, 1 charge at the provider");
    }

    // ---- (g) ----

    private Crash residualDraftPlacedNotIssued() throws Exception {
        UUID order = order("Mg");
        UUID pao = line(order, "PAO", "2");
        confirm(order, List.of(pao), "(g) round");
        UUID partition = partition(order, pao, "1", "(g) partition of one PAO (not invoiced)");
        cashier.post(POS + "/orders/" + order + "/settlement/voids", Map.of("orderVersion", version(order),
                "lineId", pao, "quantity", new BigDecimal("1"), "reason", "Pao a mais", "wasted", false), 201,
                Csrf.SEND, approver(), "(g) settled void of the pool PAO");
        pay(order, partition, "1.00", "(g) pay the partition");
        SettlementIssuer spy = AopTestUtils.getUltimateTargetObject(issuer);
        doAnswer(invocation -> {
            parkAsDead();
            return null;
        }).when(spy).issue(any(), any(), any());
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("orderVersion", cashier.get(POS + "/orders/" + order + "/settlement", 200)
                .get("orderVersion").asLong());
        String path = POS + "/orders/" + order + "/close";
        sendAndForget(path, body, key);
        Awaitility.await().atMost(RECOVERED).until(() -> count("SELECT count(*) FROM settlement"
                + ".tax_rounding_adjustment WHERE order_id = ?", order) == 1);
        Awaitility.await().atMost(RECOVERED).until(() -> count("SELECT count(*) FROM fiscal.fiscal_document "
                + "WHERE order_id = ? AND source_type = 'ORDER' AND status = 'DRAFT'", order) == 1);
        reset(spy);
        Map<String, Object> placed = jdbc.sql("""
                        SELECT a.id, a.kind, a.document_id, a.target_line_id FROM settlement.tax_rounding_adjustment a
                        WHERE a.order_id = ?""").param(order).query().singleRow();
        assertThat(placed.get("kind")).isEqualTo("ABSORBED");
        return new Crash(order, (UUID) placed.get("document_id"), key, body, path, placed);
    }

    private void recoveredG(Crash g, Browser pos) throws Exception {
        JsonNode closed = pos.post(g.path(), g.body(), 200, Csrf.SEND, Map.of("Idempotency-Key", g.key()),
                "(g) retry the close at B");
        assertThat(closed.get("lifecycle").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("documentId").asString()).isEqualTo(g.subject().toString());
        Map<String, Object> after = jdbc.sql("""
                        SELECT a.id, a.kind, a.document_id, a.target_line_id FROM settlement.tax_rounding_adjustment a
                        WHERE a.order_id = ?""").param(g.order()).query().singleRow();
        assertThat(after).as("the same row, re-applied, not placed again").isEqualTo(g.detail());
        assertThat(count("SELECT count(*) FROM fiscal.fiscal_document WHERE order_id = ? AND source_type = 'ORDER'",
                g.order())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM fiscal.fiscal_document WHERE id = ? AND status = 'ISSUED' "
                + "AND number IS NOT NULL", g.subject())).isEqualTo(1);
        assertThat(decimal("SELECT sum(t.tax) FROM fiscal.fiscal_document_tax t JOIN fiscal.fiscal_document d "
                + "ON d.id = t.document_id WHERE d.order_id = ? AND d.status = 'ISSUED'", g.order()))
                .as("Σ document tax at 23 % = the order's 0.19").isEqualByComparingTo("0.19");
        journey.add("(g) B's retried close issued the same draft with the same ABSORBED row");
    }

    // ---- (h) ----

    private Crash snapshotRefreshedNotIssued() throws Exception {
        UUID order = order("Mh");
        UUID prego = line(order, "PREGO", "1");
        UUID sumo = line(order, "SUMO", "1");
        confirm(order, List.of(prego, sumo), "(h) round");
        UUID partition = partition(order, prego, "1", "(h) partition, unpaid");
        FiscalApi spy = AopTestUtils.getUltimateTargetObject(fiscal);
        doAnswer(invocation -> {
            parkAsDead();
            return null;
        }).when(spy).issue(any(), any());
        String key = UUID.randomUUID().toString();
        String path = POS + "/partitions/" + partition + "/documents";
        sendAndForget(path, Map.of(), key);
        Awaitility.await().atMost(RECOVERED).until(() -> "UNPAID".equals(jdbc.sql(
                "SELECT payment_status FROM fiscal.fiscal_document WHERE source_id = ?").param(partition)
                .query(String.class).optional().orElse(null)));
        reset(spy);
        return new Crash(order, partition, key, Map.of(), path, null);
    }

    private void recoveredH(Crash h, Browser pos) throws Exception {
        UUID partition = h.subject();
        pos.command(POS + "/payment-attempts", attempt(h.order(), partition, "CASH", "150.00", "150.00"), 201,
                "(h) the partition is paid after the refresh");
        JsonNode issued = pos.post(h.path(), h.body(), 201, Csrf.SEND, Map.of("Idempotency-Key", h.key()),
                "(h) retry the partition document at B");
        assertThat(issued.get("status").asString()).isEqualTo("ISSUED");
        assertThat(issued.get("payments").get("status").asString()).as("the fresh snapshot").isEqualTo("PAID");
        assertThat(count("SELECT count(*) FROM fiscal.fiscal_document WHERE source_id = ? AND number IS NOT NULL",
                partition)).isEqualTo(1);
        assertThat(COUNTER.awaitPayloads(WPC1252, s -> s.contains("Documento de venda FR/"
                + issued.get("number").asLong()), 1, RECOVERED).getFirst()).contains("Pagamento — Numerário: 150.00");
        journey.add("(h) B's retry refreshed the snapshot (PAID) and issued number " + issued.get("number").asLong());
    }

    // ---- (f) ----

    private void listenerFailingTwiceIsCompletedByTheJob() throws Exception {
        UUID order = order("Mf");
        confirm(order, List.of(line(order, "AGUA", "1")), "(f) round");
        pay(order, null, "37.00", "(f) pay in cash");
        Projections spy = AopTestUtils.getUltimateTargetObject(projections);
        doThrow(new IllegalStateException("reporting down (1)")).doThrow(new IllegalStateException("reporting down (2)"))
                .doCallRealMethod().when(spy).projectDocument(any());
        UUID document;
        try {
            document = idOf(cashier.command(POS + "/orders/" + order + "/close", Map.of("orderVersion", cashier.get(
                    POS + "/orders/" + order + "/settlement", 200).get("orderVersion").asLong()), 200, "(f) close"),
                    "documentId");
            Awaitility.await().atMost(RECOVERED).until(() -> attempts(document) == 1 && failed(document));
            for (int attempt = 2; attempt <= 3; attempt++) {
                clock.advance(Duration.ofMinutes(6)); // past resubmit-older-than (PT5M)
                resubmitDue();
                int expected = attempt;
                Awaitility.await().atMost(RECOVERED).until(() -> attempts(document) == expected
                        && (failed(document) || completed(document)));
            }
        } finally {
            reset(spy);
        }
        assertThat(completed(document)).as("completed by the third attempt, without a restart").isTrue();
        assertThat(count("SELECT count(*) FROM reporting.sales_record WHERE document_id = ?", document)).isOne();
        journey.add("(f) the reporting listener failed twice; the resubmission job completed it on attempt 3");
    }

    // ---- helpers ----

    private ConfigurableApplicationContext restart() throws IOException {
        Path secret = Files.createTempFile("admin-initial-password", "");
        secret.toFile().deleteOnExit();
        Files.writeString(secret, INITIAL_PASSWORD + "\n");
        return new SpringApplicationBuilder(BackendApplication.class, SettlementTestClock.Config.class)
                .initializers(context -> context.getBeanFactory().registerSingleton("fakePaymentProvider", provider))
                .properties(
                        // application.yaml reads these placeholders; builder properties have the lowest precedence
                        "RESTAURANTPRO_DB_URL=" + freshPostgres.getJdbcUrl(),
                        "RESTAURANTPRO_DB_USERNAME=" + freshPostgres.getUsername(),
                        "RESTAURANTPRO_DB_PASSWORD=" + freshPostgres.getPassword(),
                        "RESTAURANTPRO_MANAGEMENT_PORT=0",
                        "server.port=0",
                        "server.shutdown=immediate",
                        "restaurantpro.bootstrap.company.name=" + COMPANY,
                        "restaurantpro.bootstrap.company.tax-id=400000002",
                        "restaurantpro.bootstrap.company.country=MZ",
                        "restaurantpro.bootstrap.company.currency=MZN",
                        "restaurantpro.bootstrap.admin.username=" + Installation.ADMIN,
                        "restaurantpro.bootstrap.admin.initial-password-file=" + secret,
                        "restaurantpro.settlement.provider-timeout=PT2S",
                        "restaurantpro.settlement.sweep-interval=PT1S",
                        "restaurantpro.settlement.credit-sweep-interval=PT1S",
                        "restaurantpro.settlement.in-doubt-poll-interval=PT1S",
                        "restaurantpro.production.worker.poll=PT1S")
                .run();
    }

    /** The request thread of the crashed instance: parked, holding no transaction, until the test ends; then dies. */
    private static void parkAsDead() throws InterruptedException {
        DEAD.await(5, TimeUnit.MINUTES);
        throw new IllegalStateException("the crashed instance never answers");
    }

    /** Sends a POS command whose answer never comes (the server "crashed" while handling it). */
    private void sendAndForget(String path, Object body, String key) {
        journey.add("POST " + path + " [cashier: sent to A, which crashes] -> no answer");
        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> ignored = cashier.send("POST", path, body, Csrf.SEND,
                        Map.of("Idempotency-Key", key));
            } catch (Exception expected) {
                // the client times out: "server unavailable"
            }
        });
    }

    private void resubmitDue() throws Exception {
        Method job = EventPublicationRecovery.class.getDeclaredMethod("resubmitDue");
        job.setAccessible(true);
        job.invoke(AopTestUtils.getUltimateTargetObject(recovery));
    }

    private boolean incompleteProduction(UUID batch) {
        return count("""
                SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?
                  AND listener_id LIKE '%ProductionListeners%' AND completion_date IS NULL""", "%" + batch + "%") > 0;
    }

    private int attempts(UUID document) {
        return jdbc.sql("""
                        SELECT completion_attempts FROM event_publication WHERE serialized_event LIKE ?
                          AND listener_id LIKE '%ProjectionListeners%' AND event_type LIKE '%FiscalDocumentIssued'""")
                .param("%" + document + "%").query(Integer.class).single();
    }

    private boolean failed(UUID document) {
        return "FAILED".equals(publication(document, "status"));
    }

    private boolean completed(UUID document) {
        return publication(document, "completion_date") != null;
    }

    private Object publication(UUID document, String column) {
        return jdbc.sql("SELECT " + column + " FROM event_publication WHERE serialized_event LIKE ? "
                        + "AND listener_id LIKE '%ProjectionListeners%' AND event_type LIKE '%FiscalDocumentIssued'")
                .param("%" + document + "%").query().singleValue();
    }

    private String chargeStatus(UUID order) {
        return jdbc.sql("SELECT status FROM settlement.credit_charge WHERE order_id = ?").param(order)
                .query(String.class).single();
    }

    private String attemptStatus(UUID attempt) {
        return jdbc.sql("SELECT status FROM settlement.payment_attempt WHERE id = ?").param(attempt)
                .query(String.class).single();
    }

    private UUID order(String table) throws Exception {
        UUID tableId = id(office.post(BO + "/tables", Map.of("storeId", store, "areaId", area, "code", table,
                "seats", 4), 201));
        return id(cashier.command(POS + "/tables/" + tableId + "/open", Map.of("guests", 2), 201, "open " + table));
    }

    private UUID line(UUID order, String item, String quantity) throws Exception {
        return id(cashier.command(POS + "/orders/" + order + "/lines", Map.of("itemId", items.get(item),
                "quantity", new BigDecimal(quantity)), 201, "draft " + item));
    }

    private void confirm(UUID order, List<UUID> lines, String step) throws Exception {
        cashier.command(POS + "/orders/" + order + "/batches", Map.of("orderVersion", version(order),
                "catalogVersion", catalogVersion, "lineIds", lines), 201, step);
    }

    private UUID partition(UUID order, UUID line, String quantity, String step) throws Exception {
        return id(cashier.command(POS + "/orders/" + order + "/partitions", Map.of("orderVersion", version(order),
                "kind", "ITEMS", "lines", List.of(Map.of("lineId", line, "quantity", new BigDecimal(quantity)))), 201,
                step));
    }

    private void pay(UUID order, UUID partition, String amount, String step) throws Exception {
        cashier.command(POS + "/payment-attempts", attempt(order, partition, "CASH", amount, amount), 201, step);
    }

    private long version(UUID order) throws Exception {
        return cashier.get(POS + "/orders/" + order, 200).get("version").asLong();
    }

    private Map<String, String> approver() {
        Map<String, String> headers = new LinkedHashMap<>(Browser.idem());
        headers.put("X-Approver-Code", managerCode);
        headers.put("X-Approver-Pin", MANAGER_PIN);
        return headers;
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

    private UUID printer(String code, TcpFakePrinter device, String codePage) throws Exception {
        return id(office.command(BO + "/printers", Map.of("storeId", store, "code", code, "name", "Impressora " + code,
                "host", device.host(), "port", device.port(), "codePage", codePage), 201, "printer " + code));
    }

    private static UUID idOf(JsonNode body, String field) {
        return UUID.fromString(body.get(field).asString());
    }

    private static TcpFakePrinter open() {
        try {
            return TcpFakePrinter.accepting();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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
}
