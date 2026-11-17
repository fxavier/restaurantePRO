package com.xavier.backend.e2e;

import org.testcontainers.postgresql.PostgreSQLContainer;
import static com.xavier.backend.e2e.Installation.CASHIER_ROLE;
import static com.xavier.backend.e2e.Installation.MANAGER_ROLE;
import static com.xavier.backend.e2e.Installation.id;
import static com.xavier.backend.e2e.Installation.posLogin;
import static com.xavier.backend.e2e.Installation.terminal;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.config.security.TestJwtFactory;
import com.xavier.backend.e2e.Browser.Csrf;
import com.xavier.backend.production.TcpFakePrinter;
import com.xavier.backend.settlement.FakePaymentProvider;
import com.xavier.backend.settlement.SettlementTestClock;
import com.xavier.backend.shared.ops.ExportMonitor;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.JsonNode;

/**
 * R5 end to end (PRD S63, r5-decisions §11 A62–A68; R6 S72 replaced A57–A61's local second factor and OIDC with
 * Keycloak, D175, D184): one installation through the real HTTP API of a real embedded server, with the real security
 * chain (bearer tokens validated by the resource server, the tenant resolved from the membership, step-up from the
 * token's {@code acr} and {@code auth_time} in {@code AccessPolicy}), the print worker sending ESC/POS over TCP to
 * {@link TcpFakePrinter}s, the alert sources and the management port's Prometheus scrape with its token.
 * <p>The server runs as in the Compose package behind Caddy: {@code server.forward-headers-strategy=native}, so a tab's
 * {@link Browser#at address} reaches the backend as {@code X-Forwarded-For} from the trusted loopback proxy. Tokens are
 * issued on the application clock ({@link SettlementTestClock}).
 * <ol>
 *   <li>The bootstrap administrator logs in through Keycloak and publishes store A's catalog after a step-up (a second
 *       factor 16 min old: 401 with the RFC 9470 challenge, then the same Idempotency-Key once, A87).</li>
 *   <li>Store B excludes the coffee and the delivery fee: B's release validates without their prices, lists both under
 *       {@code excludedItems} and omits the company-wide macro holding the coffee ({@code MACRO_OMITTED}); A keeps
 *       both; a POS of B gets {@code ITEM_NOT_IN_CATALOG} for the coffee and {@code FEE_ITEM_NOT_PUBLISHED} for a
 *       delivery in a zone whose fee is excluded (A62).</li>
 *   <li>Printers from the presets: kitchen EPSON_TM_80 (80/48, probe on), cashier GENERIC_58 (58/32) with a drawer. A
 *       round prints styled and wrapped; a cash payment kicks the drawer once, a card payment does not; paper out on
 *       the kitchen leaves the next job unsent in RETRY_WAIT {@code PAPER_OUT} and a print-queue alert (A63, A65,
 *       A66).</li>
 *   <li>The seven §12 alerts provoked: the tenant's own listed by {@code GET /backoffice/alerts}, the platform's
 *       (backup, disk, events) by {@code GET /platform/alerts} for platform operators only (R6); the Prometheus text
 *       scraped with the token holds every metric the Prometheus rules use ({@link AlertRulesReferenceExistingMetricsTest},
 *       A63, A64).</li>
 * </ol>
 * Provoked at the boundary rather than through HTTP, as no API can cause them: the backup status file (written as
 * {@code verify-backups.sh} would), an exhausted event publication (a row as Modulith leaves it), the export failure
 * (through the {@link ExportMonitor} bean every CSV export runs in), and the in-doubt and cash gauges' refresh (their
 * scheduled jobs, run now instead of waiting).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "server.forward-headers-strategy=native",
        "restaurantpro.metrics.token-file=" + R5AcceptanceTest.TOKEN_FILE,
        "restaurantpro.settlement.provider-timeout=PT2S",
        "restaurantpro.settlement.in-doubt-poll-interval=PT1H"})
@Import({Installation.FreshDatabase.class, SettlementTestClock.Config.class, FakePaymentProvider.Config.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R5AcceptanceTest {

    /** The §12 alerts of the platform itself: listed to platform operators, never in a tenant's BackOffice. */
    static final String[] PLATFORM_CODES = {"BACKUP_LATE", "BACKUP_PROBLEM", "DISK_SPACE_LOW", "EVENTS_FAILED"};

    @Autowired
    PostgreSQLContainer freshPostgres;

    /** The installation's owner connection, for what onboarding and Keycloak would have created. */
    private JdbcClient owner() {
        return Installation.owner(freshPostgres);
    }

    static final String TOKEN_FILE = "src/test/resources/config/metrics-token.txt";
    static final String INITIAL_PASSWORD = "unused " + UUID.randomUUID();
    static final String LAN = "192.168.1.10";
    static final String CASHIER_PIN = "5172";
    static final String MANAGER_PIN = "6283";
    static final String POS = "/api/v1/pos";
    static final String BO = "/api/v1/backoffice";
    static final String CATALOG = Installation.CATALOG;
    static final Charset CP860 = Charset.forName("IBM860");
    static final Charset WPC1252 = Charset.forName("windows-1252");
    static final Duration PRINTED = Duration.ofSeconds(20);
    static final UUID ADMINISTRATOR_ROLE = UUID.fromString("01900000-0000-7000-8000-000000000301");
    static final String LONG_DISH = "Prego no pao com queijo da serra, ovo estrelado e molho";
    static final byte STATUS_OK = 0x12;
    static final byte PAPER_END = 0x72;
    /** {@code ESC p 0 50 250}: pin 2, a 100 ms pulse (D154). */
    static final String KICK = "1b700032fa";

    static final TcpFakePrinter KITCHEN = answering();
    static final TcpFakePrinter COUNTER = open();
    static final Path BACKUP_STATUS = statusFile();

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) throws IOException {
        Installation.bootstrap(registry, "Restaurante R5", INITIAL_PASSWORD);
        registry.add("restaurantpro.alerts.backup-status-file", BACKUP_STATUS::toString);
    }

    @AfterAll
    static void unplug() throws IOException {
        KITCHEN.close();
        COUNTER.close();
    }

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SettlementTestClock clock;

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    ExportMonitor exports;

    @Autowired
    ApplicationContext context;

    private final List<String> journey = new ArrayList<>();

    Browser office;
    String adminSubject;
    UUID storeA;
    UUID storeB;
    UUID terminalA;
    UUID registerA;
    String cashierCode;
    String managerCode;
    final Map<String, UUID> items = new LinkedHashMap<>();
    UUID macro;
    UUID priceListB;
    Browser cashier;
    long catalogVersion;

    @Test
    void stageFiveEndToEnd() throws Exception {
        try {
            keycloakStepUp();
            storeItemExclusions();
            printerProfilesStyledTicketsDrawerAndPaperOut();
            theSevenAlertsAndTheScrape();
        } finally {
            clock.reset();
            System.out.println("R5 acceptance journey (step -> HTTP status):\n  " + String.join("\n  ", journey));
        }
    }

    // ---- 1. Keycloak step-up (D184, replacing A57–A60's local second factor) ----

    private void keycloakStepUp() throws Exception {
        office = new Browser("backoffice", port, journey).at(LAN);
        Installation.firstLogin(office, owner(), clock::instant);
        adminSubject = Installation.subjectOf(UUID.fromString(office.get("/api/v1/auth/session", 200)
                .get("userId").asString()));
        assertThat(office.get("/api/v1/auth/session", 200).get("permissions")).isNotEmpty();
        storeA = Installation.store(office, "A");
        storeB = Installation.store(office, "B");
        terminalA = terminal(office, storeA, "POS1");
        cashierCode = Installation.employee(office, storeA, "CX1", "Carla", CASHIER_PIN, CASHIER_ROLE);
        managerCode = Installation.employee(office, storeA, "GR1", "Gil", MANAGER_PIN, MANAGER_ROLE);
        catalog();

        // A87: a second factor 16 min old does not publish (CATALOG_PUBLISH is sensitive): the RFC 9470 challenge;
        // after the step-up, the retry with the same key publishes once
        UUID release = id(office.post(CATALOG + "/releases", Map.of("storeId", storeA), 201));
        JsonNode validatedA = office.post(CATALOG + "/releases/" + release + "/validate", null, 200);
        assertThat(validatedA.get("excludedItems")).isEmpty();
        office.signedIn(() -> TestJwtFactory.account(adminSubject, clock.instant())
                .authenticated("2", clock.instant().minus(Duration.ofMinutes(16))).encode());
        Map<String, String> key = Browser.idem();
        String publish = CATALOG + "/releases/" + release + "/publish";
        HttpResponse<String> stale = office.send("POST", publish, Map.of("note", "R5"), Csrf.SEND, key);
        office.record(office.name() + ": publish, factor 16 min old", stale);
        assertThat(stale.statusCode()).isEqualTo(401);
        assertThat(stale.headers().firstValue("WWW-Authenticate")).hasValue("Bearer "
                + "error=\"insufficient_user_authentication\", error_description=\"A different authentication level "
                + "is required\", acr_values=\"2\", max_age=300");
        assertThat(Browser.JSON.readTree(stale.body()).get("code").asString()).isEqualTo("MFA_STEP_UP_REQUIRED");
        stepUp(office);
        office.post(publish, Map.of("note", "R5"), 200, Csrf.SEND, key, "publish after the step-up");
        office.post(publish, Map.of("note", "R5"), 200, Csrf.SEND, key, "the same key again (replayed)");
        assertThat(count("SELECT count(*) FROM catalog.configuration_release WHERE store_id = ? "
                + "AND status = 'PUBLISHED'", storeA)).isEqualTo(1);
    }

    /**
     * Families, unit, VAT 16 %; items Prego (kitchen, a name longer than a line), Agua and Cafe (bar), the delivery
     * fee (FEE); A's price list prices all four, B's only Prego and Agua; the company-wide macro "Pequeno almoco"
     * (Prego + Cafe).
     */
    private void catalog() throws Exception {
        UUID family = id(office.post(CATALOG + "/families", Map.of("code", "REST", "name", "Restaurante"), 201));
        UUID unit = id(office.post(CATALOG + "/units", Map.of("code", "UN", "name", "Unidade"), 201));
        UUID tax = id(office.post(CATALOG + "/tax-categories", Map.of("code", "IVA16", "name", "IVA"), 201));
        office.post(CATALOG + "/tax-categories/" + tax + "/rates",
                Map.of("ratePercent", new BigDecimal("16"), "effectiveFrom", "2000-01-01T00:00:00Z"), 201);
        UUID priceListA = id(office.post(CATALOG + "/price-lists", Map.of("storeId", storeA, "name", "Sala A"), 201));
        priceListB = id(office.post(CATALOG + "/price-lists", Map.of("storeId", storeB, "name", "Sala B"), 201));
        String[][] dishes = {{"PREGO", LONG_DISH, "COZINHA", "150.00", "STANDARD"},
                {"AGUA", "Agua 50cl", "BAR", "40.00", "STANDARD"}, {"CAFE", "Cafe expresso", "BAR", "50.00",
                "STANDARD"}, {"TAXA", "Taxa de entrega", null, "50.00", "FEE"}};
        for (String[] dish : dishes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", dish[0]);
            item.put("name", dish[1]);
            item.put("familyId", family);
            item.put("unitId", unit);
            item.put("taxCategoryId", tax);
            item.put("productionDestination", dish[2]);
            item.put("soldByWeight", false);
            item.put("kind", dish[4]);
            UUID itemId = id(office.post(CATALOG + "/items", item, 201));
            items.put(dish[0], itemId);
            office.post(CATALOG + "/price-lists/" + priceListA + "/prices", Map.of("itemId", itemId,
                    "amount", new BigDecimal(dish[3])), 201);
            if (dish[0].equals("PREGO") || dish[0].equals("AGUA")) {
                office.post(CATALOG + "/price-lists/" + priceListB + "/prices", Map.of("itemId", itemId,
                        "amount", new BigDecimal(dish[3])), 201);
            }
        }
        macro = id(office.command(CATALOG + "/macros", Map.of("code", "PEQALM", "name", "Pequeno almoco",
                "entries", List.of(Map.of("itemId", items.get("PREGO"), "quantity", BigDecimal.ONE),
                        Map.of("itemId", items.get("CAFE"), "quantity", BigDecimal.ONE))), 201, "macro"));
    }

    // ---- 2. per-store exclusions (A62) ----

    private void storeItemExclusions() throws Exception {
        String exclusions = CATALOG + "/stores/" + storeB + "/item-exclusions/";
        office.put(exclusions + items.get("CAFE"), Map.of("reason", "Sem maquina de cafe"), 200, "B excludes CAFE");
        office.put(exclusions + items.get("TAXA"), Map.of("reason", "Sem entregas"), 200, "B excludes TAXA");
        assertThat(office.get(CATALOG + "/stores/" + storeB + "/item-exclusions", 200).get("content")).hasSize(2);

        UUID release = id(office.post(CATALOG + "/releases", Map.of("storeId", storeB), 201));
        JsonNode validated = office.post(CATALOG + "/releases/" + release + "/validate", null, 200);
        assertThat(texts(validated.get("excludedItems"), "code")).containsExactlyInAnyOrder("CAFE", "TAXA");
        assertThat(validated.get("validationWarnings")).anySatisfy(w -> {
            assertThat(w.get("code").asString()).isEqualTo("MACRO_OMITTED");
        });
        stepUp(office);
        office.command(CATALOG + "/releases/" + release + "/publish", Map.of("note", "R5 B"), 200, "publish B");

        // a POS of B
        UUID terminalB = terminal(office, storeB, "POSB");
        String waiterB = Installation.employee(office, storeB, "CXB", "Beatriz", "4826", CASHIER_ROLE);
        Browser posB = posLogin(office, terminalB, waiterB, "4826", "cashier B");
        String catalogB = posB.get(POS + "/catalog?storeId=" + storeB, 200).toString();
        assertThat(catalogB).doesNotContain(items.get("CAFE").toString()).doesNotContain(macro.toString())
                .contains(items.get("PREGO").toString());
        UUID order = id(posB.command(POS + "/orders", Map.of("mode", "COUNTER"), 201, "B: counter order"));
        JsonNode coffee = posB.command(POS + "/orders/" + order + "/lines", Map.of("itemId", items.get("CAFE"),
                "quantity", BigDecimal.ONE), 422, "B: a coffee");
        assertThat(coffee.get("code").asString()).isEqualTo("ITEM_NOT_IN_CATALOG");
        posB.command(POS + "/orders/" + order + "/lines", Map.of("itemId", items.get("PREGO"), "quantity",
                BigDecimal.ONE), 201, "B: a prego (positive control)");

        UUID zone = id(office.command(BO + "/delivery-zones", Map.of("storeId", storeB, "code", "CENTRO", "name",
                "Centro", "feeItemId", items.get("TAXA")), 201, "B: zone whose fee is excluded"));
        UUID guest = id(office.command(BO + "/customers", Map.of("name", "Rui Tembe", "phone", "842345678"), 201,
                "customer"));
        UUID address = id(office.command(BO + "/customers/" + guest + "/addresses", Map.of("kind", "DELIVERY",
                "line1", "Av. Julius Nyerere 100", "city", "Maputo"), 201, "address"));
        JsonNode delivery = posB.command(POS + "/orders", Map.of("mode", "DELIVERY", "delivery", Map.of(
                "customerId", guest, "addressId", address, "zoneId", zone)), 422, "B: open a delivery");
        assertThat(delivery.get("code").asString()).isEqualTo("FEE_ITEM_NOT_PUBLISHED");

        // store A keeps both
        cashier = posLogin(office, terminalA, cashierCode, CASHIER_PIN, "cashier");
        String catalogA = cashier.get(POS + "/catalog?storeId=" + storeA, 200).toString();
        assertThat(catalogA).contains(items.get("CAFE").toString()).contains(macro.toString());
    }

    // ---- 3. printer profiles, styled tickets, drawer, paper out (A63, A65, A66) ----

    private void printerProfilesStyledTicketsDrawerAndPaperOut() throws Exception {
        stepUp(office);
        Map<String, JsonNode> presets = new LinkedHashMap<>();
        office.get(BO + "/printer-profiles", 200).forEach(p -> presets.put(p.get("name").asString(), p.get("profile")));
        assertThat(presets).containsKeys("EPSON_TM_80", "GENERIC_80_42", "GENERIC_58");
        UUID kitchen = printer("COZ", KITCHEN, "CP860", presets.get("EPSON_TM_80"), null);
        UUID counter = printer("BALCAO", COUNTER, "WPC1252", presets.get("GENERIC_58"), 0);
        office.put(BO + "/production-routes", Map.of("storeId", storeA, "routes", List.of(
                Map.of("destinationCode", "COZINHA", "printerId", kitchen),
                Map.of("destinationCode", "BAR", "printerId", kitchen))), 200, "routes");
        office.put(BO + "/terminal-printers", Map.of("storeId", storeA, "assignments",
                List.of(Map.of("terminalId", terminalA, "printerId", counter))), 200, "terminal printer");
        registerA = id(office.command(BO + "/cash/registers", Map.of("storeId", storeA, "code", "CX1",
                "name", "Caixa 1"), 201, "register"));
        office.command(BO + "/fiscal/series", Map.of("storeId", storeA, "code", "FR", "firstNumber", 1), 201,
                "series");
        method("CARD", "Cartao (TPA)", "EXTERNAL_TERMINAL", null);
        method("MPESA", "M-Pesa", "INTEGRATED", FakePaymentProvider.CODE);

        cashier.command(POS + "/cash/sessions", Map.of("registerId", registerA, "openingFloat",
                new BigDecimal("100.00")), 201, "open session");
        catalogVersion = cashier.get(POS + "/catalog?storeId=" + storeA, 200).get("version").asLong();

        // a round: styled and wrapped for 80/48 (D155), after the status probe (D157)
        UUID cash = counterOrder("PREGO", "cash order round");
        assertThat(KITCHEN.awaitPayloads(CP860, s -> s.contains("Prego no pao"), 1, PRINTED)).hasSize(1);
        byte[] round = KITCHEN.received().stream().filter(b -> new String(b, CP860).contains("Prego no pao"))
                .findFirst().orElseThrow();
        String text = new String(round, CP860);
        assertThat(HexFormat.of().formatHex(Arrays.copyOf(round, 8))).as("probe, then ESC @")
                .isEqualTo("1004021004041b40");
        assertThat(text).contains("\u001Ba\u0001\u001D!\u0011").as("a DOUBLE, CENTER title")
                .contains("\u001D!\u0001" + "1 x Prego").as("TALL item lines")
                .endsWith("\u001Bd\u0004\u001DVB\u0000");
        String first = text.substring(text.indexOf("1 x Prego"), text.indexOf('\n', text.indexOf("1 x Prego")));
        String rest = text.substring(text.indexOf("1 x Prego") + first.length() + 1);
        String continuation = rest.substring(0, rest.indexOf('\n'));
        assertThat(first.length()).as("wrapped at 48 columns: %s", first).isLessThanOrEqualTo(48);
        assertThat(continuation).as("indented after \"1 x \"").startsWith("    ").doesNotStartWith("     ");
        assertThat((first + " " + continuation.strip()).replace("1 x ", "")).isEqualTo(LONG_DISH);

        // A66: the cash payment kicks the cashier's drawer once; the card payment does not
        pay(cash, "CASH", "150.00", "pay cash");
        Awaitility.await().atMost(PRINTED).until(() -> kicks() == 1);
        close(cash, "close the cash order");
        UUID card = counterOrder("AGUA", "card order round");
        JsonNode attempt = cashier.command(POS + "/payment-attempts", attempt(card, "CARD", "40.00", null), 201,
                "pay by card");
        cashier.command(POS + "/payment-attempts/" + id(attempt) + "/confirm", Map.of("authorizationCode",
                "TPA-1"), 200, "confirm card");
        close(card, "close the card order");
        List<String> documents = COUNTER.awaitPayloads(WPC1252, s -> s.contains("Documento de venda"), 2, PRINTED);
        assertThat(documents).as("both documents printed (58 mm, no cut)").hasSize(2)
                .allSatisfy(d -> assertThat(d).doesNotContain("\u001DV"));
        assertThat(kicks()).as("one kick: the card payment opened nothing").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM production.production_ticket WHERE source_type = 'DRAWER_KICK'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM event_publication WHERE event_type LIKE '%CashDrawerKickRequested'"))
                .isEqualTo(1);

        // paper out on the kitchen: nothing written, RETRY_WAIT PAPER_OUT, then a print-queue alert (the card
        // order's round printed first, so every later connection is the paper-out job's)
        assertThat(KITCHEN.awaitPayloads(CP860, s -> s.contains("Agua 50cl"), 1, PRINTED)).hasSize(1);
        int before = KITCHEN.received().size();
        KITCHEN.answer(STATUS_OK, PAPER_END);
        UUID stuck = counterOrder("PREGO", "round while the kitchen is out of paper");
        Awaitility.await().atMost(PRINTED).until(() -> "PAPER_OUT".equals(jdbc.sql("""
                SELECT j.last_error FROM production.print_job j JOIN production.production_ticket t
                  ON t.id = j.ticket_id WHERE t.order_id = ? AND t.source_type = 'BATCH'""").param(stuck)
                .query(String.class).optional().orElse(null)));
        assertThat(KITCHEN.received().subList(before, KITCHEN.received().size())).isNotEmpty()
                .allSatisfy(b -> assertThat(HexFormat.of().formatHex(b)).as("only the probe").isEqualTo("100402100404"));
        clock.advance(Duration.ofMinutes(6));
        Awaitility.await().atMost(PRINTED).untilAsserted(() -> assertThat(alerts()).anySatisfy(a -> {
            assertThat(a.get("code").asString()).isIn("PRINT_QUEUE_STALLED", "PRINT_JOBS_ATTENTION");
            assertThat(a.get("storeId").asString()).isEqualTo(storeA.toString());
        }));
    }

    // ---- 4. the seven alerts and the scrape (A63, A64) ----

    private void theSevenAlertsAndTheScrape() throws Exception {
        // cash: the manager's register is 5.00 short, closed and submitted
        UUID terminal2 = terminal(office, storeA, "POS2");
        Browser manager = posLogin(office, terminal2, managerCode, MANAGER_PIN, "manager");
        UUID register2 = id(office.command(BO + "/cash/registers", Map.of("storeId", storeA, "code", "CX2",
                "name", "Caixa 2"), 201, "register 2"));
        JsonNode opened = manager.command(POS + "/cash/sessions", Map.of("registerId", register2, "openingFloat",
                new BigDecimal("100.00")), 201, "manager opens register 2");
        UUID session = id(opened);
        UUID cashDay = UUID.fromString(opened.get("cashDayId").asString());
        JsonNode current = manager.get(POS + "/cash/sessions/" + session, 200);
        manager.command(POS + "/cash/sessions/" + session + "/close", Map.of("version", current.get("version")
                .asLong(), "counts", List.of(Map.of("methodCode", "CASH", "amount", new BigDecimal("95.00")))),
                200, "close register 2, 5.00 short");
        JsonNode draft = manager.command(POS + "/cash/closings", Map.of("registerId", register2, "cashDayId", cashDay),
                201, "closing of register 2");
        manager.put(POS + "/cash/closings/" + id(draft), Map.of("version", draft.get("version").asLong(),
                "justifications", List.of(Map.of("methodCode", "CASH", "text", "Troco errado"))), 200,
                "justify the difference");
        manager.command(POS + "/cash/closings/" + id(draft) + "/submit", null, 200, "submit the closing");

        // an integrated charge left in doubt
        UUID doubtful = counterOrder("AGUA", "round paid by M-Pesa");
        provider.willNext(FakePaymentProvider.Behaviour.HANG);
        UUID charge = id(cashier.command(POS + "/payment-attempts", attempt(doubtful, "MPESA", "40.00", null), 202,
                "M-Pesa charge (times out)"));
        Awaitility.await().atMost(PRINTED).until(() -> "UNKNOWN".equals(jdbc.sql(
                "SELECT status FROM settlement.payment_attempt WHERE id = ?").param(charge).query(String.class)
                .single()));
        provider.willNext(FakePaymentProvider.Behaviour.SUCCEED);

        // an event publication that failed every retry
        UUID publication = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,
                    completion_date, status, completion_attempts)
                VALUES (?, 'com.xavier.backend.reporting.service.ProvokedFailure.on(java.lang.Object)', 'java.lang.Object', '{}',
                    now() - interval '1 hour', NULL, 'FAILED', 10)""").param(publication).update();

        // an export that fails inside the monitor every CSV export runs in
        try {
            exports.monitor("report_csv", () -> {
                throw new IllegalStateException("provoked export failure");
            });
        } catch (IllegalStateException expected) {
            journey.add("report_csv export failed (provoked)");
        }

        // 31 min on: the charge is overdue; then the gauges' jobs run and verify-backups.sh writes its status
        clock.advance(Duration.ofMinutes(31));
        runScheduled("inDoubtGauge");
        runScheduled("cashDiscrepancyAlertSource");
        runScheduled("eventPublicationStats");
        Instant now = clock.instant();
        Files.writeString(BACKUP_STATUS, """
                {"schemaVersion": 1, "checkedAt": "%s", "ok": false,
                 "lastLogicalBackupAt": "%s", "lastBaseBackupAt": "%s", "lastOffboxSyncAt": "%s",
                 "problems": ["the last logical backup is older than 26 h"],
                 "filesystems": [{"name": "backups", "totalBytes": 1000, "freeBytes": 30},
                                 {"name": "docker", "totalBytes": 1000, "freeBytes": 500}]}"""
                .formatted(now.minusSeconds(60), now.minus(Duration.ofHours(30)), now.minus(Duration.ofHours(2)),
                        now.minus(Duration.ofMinutes(5))));
        clock.advance(Duration.ofMinutes(2)); // the reader reads at most once a minute

        // the tenant's own alerts in its BackOffice; the platform's to platform operators only (R6 user decision)
        List<JsonNode> listed = alerts();
        Map<String, String> byCode = byCode(listed);
        assertThat(byCode).containsKeys("PAYMENTS_IN_DOUBT", "CASH_DISCREPANCY", "EXPORT_FAILED")
                .doesNotContainKeys(PLATFORM_CODES);
        assertThat(byCode.keySet()).containsAnyOf("PRINT_QUEUE_STALLED", "PRINT_JOBS_ATTENTION");
        assertThat(byCode).containsEntry("PAYMENTS_IN_DOUBT", storeA.toString())
                .containsEntry("CASH_DISCREPANCY", storeA.toString());
        assertThat(listed.getFirst().get("severity").asString()).as("CRITICAL first").isEqualTo("CRITICAL");
        List<JsonNode> platform = platformAlerts();
        assertThat(byCode(platform)).containsKeys(PLATFORM_CODES).containsEntry("BACKUP_LATE", null)
                .containsEntry("EVENTS_FAILED", null);
        assertThat(platform.getFirst().get("severity").asString()).as("CRITICAL first").isEqualTo("CRITICAL");

        // the scrape: 401 without the token, 200 with it, holding every metric the Prometheus rules use
        assertThat(scrape(null).statusCode()).isEqualTo(401);
        HttpResponse<String> scraped = scrape(Files.readString(Path.of(TOKEN_FILE)).strip());
        assertThat(scraped.statusCode()).isEqualTo(200);
        assertThat(AlertRulesReferenceExistingMetricsTest.missingFrom(scraped.body())).as("rule metrics missing")
                .isEmpty();
        assertThat(scraped.body()).doesNotContain(charge.toString()).doesNotContain(publication.toString());
        journey.add("GET /actuator/prometheus [token] -> 200, every rule metric present");

        // the failed publication cleared: its alert goes
        jdbc.sql("DELETE FROM event_publication WHERE id = ?").param(publication).update();
        assertThat(platformAlerts()).noneSatisfy(a -> assertThat(a.get("code").asString())
                .isEqualTo("EVENTS_FAILED"));
    }

    // ---- helpers ----

    /** Enrols the tab's account (from where the tab is), returns the secret; the session ends VERIFIED. */
    /** The user authenticates again through Keycloak (the BFF's step-up, D184): fresh tokens from now on. */
    private void stepUp(Browser tab) {
        tab.signedIn(() -> TestJwtFactory.account(adminSubject, clock.instant()).encode());
        journey.add(tab.name() + ": step-up through Keycloak");
    }

    private UUID printer(String code, TcpFakePrinter device, String codePage, JsonNode preset, Integer drawerPin)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storeId", storeA);
        body.put("code", code);
        body.put("name", "Impressora " + code);
        body.put("host", device.host());
        body.put("port", device.port());
        body.put("codePage", codePage);
        body.put("paperWidthMm", preset.get("paperWidthMm").asInt());
        body.put("columns", preset.get("columns").asInt());
        body.put("cutMode", preset.get("cutMode").asString());
        body.put("feedLines", preset.get("feedLines").asInt());
        body.put("statusProbe", preset.get("statusProbe").asBoolean());
        body.put("drawerPin", drawerPin);
        body.put("drawerPulseMs", 100);
        JsonNode created = office.command(BO + "/printers", body, 201, "printer " + code);
        assertThat(created.get("profile").get("columns").asInt()).isEqualTo(preset.get("columns").asInt());
        return id(created);
    }

    private void method(String code, String name, String kind, String providerCode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("kind", kind);
        body.put("providerCode", providerCode);
        office.command(BO + "/payment-methods", body, 201, "method " + code);
    }

    /** A counter order with one {@code item}, its round confirmed. */
    private UUID counterOrder(String item, String step) throws Exception {
        UUID order = id(cashier.command(POS + "/orders", Map.of("mode", "COUNTER"), 201, "counter order"));
        UUID line = id(cashier.command(POS + "/orders/" + order + "/lines", Map.of("itemId", items.get(item),
                "quantity", BigDecimal.ONE), 201, "draft " + item));
        cashier.command(POS + "/orders/" + order + "/batches", Map.of("orderVersion", version(order),
                "catalogVersion", catalogVersion, "lineIds", List.of(line)), 201, step);
        return order;
    }

    private void pay(UUID order, String method, String amount, String step) throws Exception {
        cashier.command(POS + "/payment-attempts", attempt(order, method, amount, amount), 201, step);
    }

    private void close(UUID order, String step) throws Exception {
        cashier.command(POS + "/orders/" + order + "/close", Map.of("orderVersion",
                cashier.get(POS + "/orders/" + order + "/settlement", 200).get("orderVersion").asLong()), 200, step);
    }

    private long version(UUID order) throws Exception {
        return cashier.get(POS + "/orders/" + order, 200).get("version").asLong();
    }

    private static Map<String, Object> attempt(UUID order, String method, String amount, String tendered) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", order);
        body.put("partitionId", null);
        body.put("methodCode", method);
        body.put("amount", new BigDecimal(amount));
        body.put("tendered", tendered == null ? null : new BigDecimal(tendered));
        return body;
    }

    /** Connections to the cashier's printer that carried exactly a drawer kick. */
    private long kicks() {
        return COUNTER.received().stream().filter(b -> HexFormat.of().formatHex(b).equals(KICK)).count();
    }

    /** {@code GET /backoffice/alerts} past the 30 s cache. */
    private List<JsonNode> alerts() throws Exception {
        clock.advance(Duration.ofSeconds(31));
        return StreamSupport.stream(office.get(BO + "/alerts", 200).get("alerts").spliterator(), false).toList();
    }

    /** The platform's alerts, as a platform operator (ops realm, {@code platform-operator}) lists them. */
    private List<JsonNode> platformAlerts() throws Exception {
        clock.advance(Duration.ofSeconds(31));
        Browser operator = new Browser("platform-operator", port, journey)
                .signedIn(() -> TestJwtFactory.support("operator-" + UUID.randomUUID(), clock.instant())
                        .claim("realm_access", Map.of("roles", List.of("platform-operator"))).encode());
        return StreamSupport.stream(operator.get("/api/v1/platform/alerts", 200).get("alerts").spliterator(), false)
                .toList();
    }

    private static Map<String, String> byCode(List<JsonNode> alerts) {
        Map<String, String> byCode = new LinkedHashMap<>();
        alerts.forEach(a -> byCode.put(a.get("code").asString(), a.get("storeId").isNull() ? null
                : a.get("storeId").asString()));
        return byCode;
    }

    private HttpResponse<String> scrape(String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort
                + "/actuator/prometheus")).timeout(Duration.ofSeconds(30)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Runs the {@code @Scheduled} refresh of a module-internal bean now, instead of waiting for its interval. */
    private void runScheduled(String bean) throws Exception {
        Object target = AopTestUtils.getUltimateTargetObject(context.getBean(bean));
        Method refresh = target.getClass().getDeclaredMethod("refresh");
        refresh.setAccessible(true);
        refresh.invoke(target);
    }

    private static Set<String> texts(JsonNode array, String field) {
        return StreamSupport.stream(array.spliterator(), false).map(n -> n.get(field).asString())
                .collect(Collectors.toSet());
    }

    private long count(String sql, Object... params) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        for (Object param : params) {
            statement = statement.param(param);
        }
        return statement.query(Long.class).single();
    }

    private static TcpFakePrinter open() {
        try {
            return TcpFakePrinter.accepting();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TcpFakePrinter answering() {
        try {
            return TcpFakePrinter.answering(STATUS_OK, STATUS_OK);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path statusFile() {
        try {
            Path file = Files.createTempFile("backup-status", ".json");
            file.toFile().deleteOnExit();
            Files.delete(file); // configured but not written yet, as before the first verify-backups.sh run
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
