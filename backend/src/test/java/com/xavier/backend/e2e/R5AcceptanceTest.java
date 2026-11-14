package com.xavier.backend.e2e;

import static com.xavier.backend.e2e.Installation.CASHIER_ROLE;
import static com.xavier.backend.e2e.Installation.MANAGER_ROLE;
import static com.xavier.backend.e2e.Installation.id;
import static com.xavier.backend.e2e.Installation.posLogin;
import static com.xavier.backend.e2e.Installation.terminal;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.e2e.Browser.Csrf;
import com.xavier.backend.identity.SwitchableMfaPolicy;
import com.xavier.backend.identity.mfa.Base32;
import com.xavier.backend.identity.mfa.MfaMode;
import com.xavier.backend.identity.mfa.Totp;
import com.xavier.backend.identity.oidc.StubIdp;
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
 * R5 end to end (PRD S63, r5-decisions §11 A57–A68): one installation through the real HTTP API of a real embedded
 * server, with the real security chain (sessions in PostgreSQL, CSRF, the MFA filters, step-up in
 * {@code AccessPolicy}, OIDC login against a {@link StubIdp}), the print worker sending ESC/POS over TCP to
 * {@link TcpFakePrinter}s, the alert sources and the management port's Prometheus scrape with its token.
 * <p>The server runs as in the Compose package behind Caddy: {@code server.forward-headers-strategy=native}, so a tab's
 * {@link Browser#at address} reaches the backend as {@code X-Forwarded-For} from the trusted loopback proxy;
 * {@code lan-cidrs} is the store LAN {@code 192.168.1.0/24} only. The MFA mode is {@code SENSITIVE} (P52), switched to
 * {@code ALL_BACKOFFICE} for step 5 through {@link SwitchableMfaPolicy} (the deployment property, changed without a
 * restart). TOTP codes come from the enrolment's secret and the application clock ({@link SettlementTestClock}),
 * moved one 30 s step before each code so the replay guard never refuses one.
 * <ol>
 *   <li>The bootstrap administrator logs in from the LAN without a code, is refused a sensitive action until enrolled,
 *       enrols, publishes store A's catalog after a step-up (16 min later: 403 {@code MFA_STEP_UP_REQUIRED}, then the
 *       same Idempotency-Key once), and resets the MFA of a second administrator, who enrols from outside with the
 *       enrolment code (A57–A60).</li>
 *   <li>Store B excludes the coffee and the delivery fee: B's release validates without their prices, lists both under
 *       {@code excludedItems} and omits the company-wide macro holding the coffee ({@code MACRO_OMITTED}); A keeps
 *       both; a POS of B gets {@code ITEM_NOT_IN_CATALOG} for the coffee and {@code FEE_ITEM_NOT_PUBLISHED} for a
 *       delivery in a zone whose fee is excluded (A62).</li>
 *   <li>Printers from the presets: kitchen EPSON_TM_80 (80/48, probe on), cashier GENERIC_58 (58/32) with a drawer. A
 *       round prints styled and wrapped; a cash payment kicks the drawer once, a card payment does not; paper out on
 *       the kitchen leaves the next job unsent in RETRY_WAIT {@code PAPER_OUT} and a print-queue alert (A63, A65,
 *       A66).</li>
 *   <li>The seven §12 alerts provoked and listed by {@code GET /backoffice/alerts}; the Prometheus text scraped with the
 *       token holds every metric the Prometheus rules use ({@link AlertRulesReferenceExistingMetricsTest}, A63, A64).</li>
 *   <li>OIDC: the administrator links, logs in federated, lands in {@code MFA_PENDING} (mode ALL_BACKOFFICE) and
 *       verifies; with the IdP stopped, the password login still works (A61).</li>
 * </ol>
 * Provoked at the boundary rather than through HTTP, as no API can cause them: the backup status file (written as
 * {@code verify-backups.sh} would), an exhausted event publication (a row as Modulith leaves it), the export failure
 * (through the {@link ExportMonitor} bean every CSV export runs in), and the in-doubt and cash gauges' refresh (their
 * scheduled jobs, run now instead of waiting).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "server.forward-headers-strategy=native",
        "restaurantpro.security.mfa.mode=SENSITIVE",
        "restaurantpro.security.mfa.key-file=src/test/resources/identity/mfa-test.key",
        "restaurantpro.security.lan-cidrs=192.168.1.0/24",
        "restaurantpro.metrics.token-file=" + R5AcceptanceTest.TOKEN_FILE,
        "restaurantpro.settlement.provider-timeout=PT2S",
        "restaurantpro.settlement.in-doubt-poll-interval=PT1H"})
@Import({Installation.FreshDatabase.class, SettlementTestClock.Config.class, FakePaymentProvider.Config.class,
        SwitchableMfaPolicy.Config.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R5AcceptanceTest {

    static final String TOKEN_FILE = "src/test/resources/config/metrics-token.txt";
    static final String INITIAL_PASSWORD = "initial " + UUID.randomUUID();
    static final String NEW_PASSWORD = "changed " + UUID.randomUUID();
    static final String LAN = "192.168.1.10";
    static final String REMOTE = "203.0.113.5";
    static final String CASHIER_PIN = "5172";
    static final String MANAGER_PIN = "6283";
    static final String POS = "/api/v1/pos";
    static final String BO = "/api/v1/backoffice";
    static final String MFA = "/api/v1/auth/mfa";
    static final String OIDC = "/api/v1/auth/oidc";
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
    static final StubIdp IDP = StubIdp.start();
    static final Path BACKUP_STATUS = statusFile();

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) throws IOException {
        Installation.bootstrap(registry, "Restaurante R5", INITIAL_PASSWORD);
        registry.add("restaurantpro.alerts.backup-status-file", BACKUP_STATUS::toString);
        registry.add("restaurantpro.security.oidc.enabled", () -> "true");
        registry.add("restaurantpro.security.oidc.issuer-uri", IDP::issuer);
        registry.add("restaurantpro.security.oidc.client-id", () -> StubIdp.CLIENT_ID);
        registry.add("restaurantpro.security.oidc.client-secret-file",
                () -> "src/test/resources/identity/oidc-client-secret.txt");
    }

    @AfterAll
    static void unplug() throws IOException {
        KITCHEN.close();
        COUNTER.close();
        IDP.close();
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
    SwitchableMfaPolicy mfaPolicy;

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    ExportMonitor exports;

    @Autowired
    ApplicationContext context;

    private final List<String> journey = new ArrayList<>();

    Browser office;
    byte[] ownerSecret;
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
            mfaPolicy.set(MfaMode.SENSITIVE);
            secondFactorStepUpAndRemoteEnrolment();
            storeItemExclusions();
            printerProfilesStyledTicketsDrawerAndPaperOut();
            theSevenAlertsAndTheScrape();
            federatedLoginAndPasswordFallback();
        } finally {
            mfaPolicy.reset();
            clock.reset();
            System.out.println("R5 acceptance journey (step -> HTTP status):\n  " + String.join("\n  ", journey));
        }
    }

    // ---- 1. second factor, step-up, reset, remote enrolment (A57–A60) ----

    private void secondFactorStepUpAndRemoteEnrolment() throws Exception {
        office = new Browser("backoffice", port, journey).at(LAN);
        Installation.firstLogin(office, INITIAL_PASSWORD, NEW_PASSWORD);
        assertThat(office.get("/api/v1/auth/session", 200).get("permissions")).isNotEmpty();
        storeA = Installation.store(office, "A");
        storeB = Installation.store(office, "B");
        JsonNode refused = office.command(BO + "/terminals", Map.of("storeId", storeA, "code", "POS1", "name",
                "POS1"), 403, "a sensitive action before enrolling");
        assertThat(refused.get("code").asString()).isEqualTo("MFA_ENROLMENT_REQUIRED");

        ownerSecret = enrol(office, NEW_PASSWORD, null);
        terminalA = terminal(office, storeA, "POS1");
        cashierCode = Installation.employee(office, storeA, "CX1", "Carla", CASHIER_PIN, CASHIER_ROLE);
        managerCode = Installation.employee(office, storeA, "GR1", "Gil", MANAGER_PIN, MANAGER_ROLE);
        catalog();

        // A59: 16 min after the second factor, publishing (CATALOG_PUBLISH) needs a step-up; the retry with the
        // same key publishes once
        UUID release = id(office.post(CATALOG + "/releases", Map.of("storeId", storeA), 201));
        JsonNode validatedA = office.post(CATALOG + "/releases/" + release + "/validate", null, 200);
        assertThat(validatedA.get("excludedItems")).isEmpty();
        clock.advance(Duration.ofMinutes(16));
        Map<String, String> key = Browser.idem();
        String publish = CATALOG + "/releases/" + release + "/publish";
        JsonNode stale = office.post(publish, Map.of("note", "R5"), 403, Csrf.SEND, key, "publish, factor 16 min old");
        assertThat(stale.get("code").asString()).isEqualTo("MFA_STEP_UP_REQUIRED");
        assertThat(stale.get("stepUpUri").asString()).isEqualTo(MFA + "/step-up");
        stepUp(office, ownerSecret);
        office.post(publish, Map.of("note", "R5"), 200, Csrf.SEND, key, "publish after the step-up");
        office.post(publish, Map.of("note", "R5"), 200, Csrf.SEND, key, "the same key again (replayed)");
        assertThat(count("SELECT count(*) FROM catalog.configuration_release WHERE store_id = ? "
                + "AND status = 'PUBLISHED'", storeA)).isEqualTo(1);

        // A60, A58: a second administrator enrolled on the LAN; reset; she enrols from outside with the code
        String password = "pw " + UUID.randomUUID();
        UUID gestora = id(office.post(BO + "/users", Map.of("username", "gestora", "initialPassword", password,
                "roleIds", List.of(ADMINISTRATOR_ROLE)), 201));
        Browser second = office.sibling("gestora");
        second.get("/api/v1/auth/session", 401);
        second.post("/api/v1/auth/login", Map.of("username", "gestora", "password", password), 200);
        second.post("/api/v1/auth/password", Map.of("currentPassword", password, "newPassword", password + " 2"),
                200);
        password = password + " 2";
        enrol(second, password, null);
        second.get(BO + "/stores", 200);

        JsonNode reset = office.post(BO + "/users/" + gestora + "/mfa/reset", Map.of("reason",
                "Telemovel perdido"), 200, "reset the second administrator's MFA");
        String enrolmentCode = reset.get("enrolmentCode").asString();
        office.post(BO + "/users/" + office.get("/api/v1/auth/session", 200).get("userId").asString()
                + "/mfa/reset", Map.of("reason", "eu"), 422, "resetting oneself");
        second.get("/api/v1/auth/session", 401);

        second.at(REMOTE);
        JsonNode notEnrolled = second.post("/api/v1/auth/login", Map.of("username", "gestora", "password",
                password), 403, "remote login without a factor");
        assertThat(notEnrolled.get("code").asString()).isEqualTo("MFA_NOT_ENROLLED");
        JsonNode enrolling = second.post("/api/v1/auth/login", Map.of("username", "gestora", "password", password,
                "enrolmentCode", enrolmentCode), 200, "remote login with the enrolment code");
        assertThat(enrolling.get("mfa").get("state").asString()).isEqualTo("ENROLMENT_REQUIRED");
        assertThat(enrolling.get("permissions")).isEmpty();
        second.get(BO + "/stores", 403);
        enrol(second, password, enrolmentCode);
        second.get(BO + "/stores", 200);
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'MFA_RESET' AND entity_id = ?",
                gestora.toString())).isEqualTo(1);
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
        stepUp(office, ownerSecret);
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
        stepUp(office, ownerSecret);
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

        List<JsonNode> listed = alerts();
        Map<String, String> byCode = new LinkedHashMap<>();
        listed.forEach(a -> byCode.put(a.get("code").asString(), a.get("storeId").isNull() ? null
                : a.get("storeId").asString()));
        assertThat(byCode).containsKeys("BACKUP_LATE", "BACKUP_PROBLEM", "DISK_SPACE_LOW", "EVENTS_FAILED",
                "PAYMENTS_IN_DOUBT", "CASH_DISCREPANCY", "EXPORT_FAILED");
        assertThat(byCode.keySet()).containsAnyOf("PRINT_QUEUE_STALLED", "PRINT_JOBS_ATTENTION");
        assertThat(byCode).containsEntry("PAYMENTS_IN_DOUBT", storeA.toString())
                .containsEntry("CASH_DISCREPANCY", storeA.toString()).containsEntry("BACKUP_LATE", null)
                .containsEntry("EVENTS_FAILED", null);
        assertThat(listed.getFirst().get("severity").asString()).as("CRITICAL first").isEqualTo("CRITICAL");

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
        assertThat(alerts()).noneSatisfy(a -> assertThat(a.get("code").asString()).isEqualTo("EVENTS_FAILED"));
    }

    // ---- 5. OIDC (A61) ----

    private void federatedLoginAndPasswordFallback() throws Exception {
        mfaPolicy.set(MfaMode.ALL_BACKOFFICE);
        String subject = "sub-" + UUID.randomUUID();
        stepUp(office, ownerSecret);
        assertThat(office.post(OIDC + "/link", null, 200, "link: ask").get("authorizeUri").asString())
                .isEqualTo(OIDC + "/authorize");
        assertThat(federate(office, subject)).isEqualTo("/backoffice/");
        office.get(OIDC + "/link", 200);

        Browser sso = new Browser("sso", port, journey).at(LAN);
        sso.get("/api/v1/auth/session", 401);
        assertThat(federate(sso, subject)).isEqualTo("/backoffice/");
        JsonNode pending = sso.get("/api/v1/auth/session", 200);
        assertThat(pending.get("mfa").get("state").asString()).isEqualTo("PENDING");
        sso.get(BO + "/stores", 403);
        clock.advance(Duration.ofSeconds(Totp.PERIOD));
        JsonNode verified = sso.post(MFA + "/verify", Map.of("code", code(ownerSecret)), 200, "verify");
        sso.get(BO + "/stores", 200);
        assertThat(verified.toString()).contains("VERIFIED");

        IDP.stop();
        Browser password = new Browser("password", port, journey).at(LAN);
        password.get("/api/v1/auth/session", 401);
        JsonNode login = password.post("/api/v1/auth/login", Map.of("username", Installation.ADMIN, "password",
                NEW_PASSWORD), 200, "password login, the IdP down");
        assertThat(login.get("mfa").get("state").asString()).isEqualTo("PENDING");
        clock.advance(Duration.ofSeconds(Totp.PERIOD));
        password.post(MFA + "/verify", Map.of("code", code(ownerSecret)), 200, "verify");
        password.get(BO + "/stores", 200);
    }

    /** The authorization-code round trip for {@code subject} in {@code tab}; the path the callback redirects to. */
    private String federate(Browser tab, String subject) throws Exception {
        String toIdp = redirect(tab, OIDC + "/authorize");
        assertThat(toIdp).startsWith(IDP.issuer() + "/authorize?");
        Map<String, String> params = Arrays.stream(URI.create(toIdp).getRawQuery().split("&"))
                .map(p -> p.split("=", 2)).collect(Collectors.toMap(p -> p[0],
                        p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
        String code = "code-" + UUID.randomUUID();
        IDP.issue(code, StubIdp.Grant.of(subject, params.get("nonce"), params.get("code_challenge")));
        return URI.create(redirect(tab, OIDC + "/callback?code=" + code + "&state="
                + URLEncoder.encode(params.get("state"), StandardCharsets.UTF_8))).getPath();
    }

    private String redirect(Browser tab, String path) throws Exception {
        HttpResponse<String> response = tab.send("GET", path, null, Csrf.OMIT, Map.of());
        tab.record(tab.name() + ": OIDC", response);
        assertThat(response.statusCode()).as("GET %s answered %s", path, response.body()).isEqualTo(302);
        return response.headers().firstValue("Location").orElseThrow();
    }

    // ---- helpers ----

    /** Enrols the tab's account (from where the tab is), returns the secret; the session ends VERIFIED. */
    private byte[] enrol(Browser tab, String currentPassword, String enrolmentCode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentPassword", currentPassword);
        body.put("enrolmentCode", enrolmentCode);
        JsonNode started = tab.post(MFA + "/enrolment", body, 200, "start enrolment");
        byte[] secret = Base32.decode(started.get("secret").asString());
        clock.advance(Duration.ofSeconds(Totp.PERIOD));
        JsonNode confirmed = tab.post(MFA + "/enrolment/confirm", Map.of("code", code(secret)), 200,
                "confirm enrolment");
        assertThat(confirmed.get("recoveryCodes")).hasSize(10);
        assertThat(confirmed.get("session").get("mfa").get("state").asString()).isEqualTo("VERIFIED");
        return secret;
    }

    private void stepUp(Browser tab, byte[] secret) throws Exception {
        clock.advance(Duration.ofSeconds(Totp.PERIOD));
        tab.post(MFA + "/step-up", Map.of("code", code(secret)), 200, "step-up");
    }

    private String code(byte[] secret) {
        return "%06d".formatted(Totp.codeAt(secret, Totp.stepAt(clock.instant())));
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
