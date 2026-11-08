package com.xavier.backend.e2e;

import static com.xavier.backend.e2e.Installation.CASHIER_ROLE;
import static com.xavier.backend.e2e.Installation.MANAGER_ROLE;
import static com.xavier.backend.e2e.Installation.id;
import static com.xavier.backend.e2e.Installation.posLogin;
import static com.xavier.backend.e2e.Installation.terminal;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.e2e.Browser.Csrf;
import com.xavier.backend.production.TcpFakePrinter;
import com.xavier.backend.settlement.SettlementTestClock;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * R4 end to end (PRD S54, r4-decisions §17 A38–A52): one business day of a store with stock, promotions, loyalty,
 * a reservation and a delivery, through the real HTTP API of a real embedded server, with the real security chain,
 * CSRF, sessions in PostgreSQL, the production print worker sending ESC/POS over TCP to {@link TcpFakePrinter}s, the
 * internal fiscal adapter, the inventory and reporting listeners, and the application clock moved to 18:00 store time
 * ({@link SettlementTestClock}, so the happy hour 17:00–19:00 is on and the 05:00 cutoff is 11 h away); nothing is
 * mocked.
 * <ol>
 *   <li>Setup: store A with kitchen and bar warehouses and routes, live in inventory through an opening count per
 *       warehouse, stock items, recipes (the prego's box only for takeaway and delivery), a menu, a happy hour on the
 *       juice, discount limits 10/50 %, a loyalty program with a free-coffee reward, a member customer, a delivery
 *       zone with a fee item; store B with one warehouse.</li>
 *   <li>A purchase order of 20 steaks, two partial receipts at 10.00 and 12.00 (PARTIAL, then FULL; average 11.00),
 *       then a cost correction of the second to 13.00 (average 11.50).</li>
 *   <li>A reservation shows on table M1 and is seated by its open; a round during the happy hour (juice 60.00 →
 *       48.00) with a menu whose starter goes to the kitchen and drink to the bar; an offer of one of three coffees
 *       (split, approver).</li>
 *   <li>A void not wasted (prego), a void wasted (juice), a free coffee redeemed; split by items, paid, closed: one
 *       EARN of ⌊428.00 ÷ 10⌋ = 42 points.</li>
 *   <li>A delivery: fee line, kitchen ticket ENTREGA, dispatch with slip, close refused until delivered, cash by the
 *       cashier, close.</li>
 *   <li>A count of the bar during service (a coffee sold after its cut-off is no divergence), then a transfer of 5
 *       steaks to store B that arrives with 4.</li>
 *   <li>Margins, discounts, stock valuation and the documents agree; the reporting rebuild and the inventory balance
 *       rebuild give identical results.</li>
 * </ol>
 *
 * <p>Every amount is worked out by hand in the comment next to it. Prices are gross with 16 % VAT and tax is per
 * line (D59): net = round(gross ÷ 1.16), tax = gross − net. 150.00 → 129.31 + 20.69; 180.00 → 155.17 + 24.83;
 * 60.00 → 51.72 + 8.28; 48.00 → 41.38 + 6.62; 50.00 → 43.10 + 6.90. Stock is in units (un) and every average below
 * stays exact, so an OUT of q from (Q, V) takes −round4(V × q ÷ Q) = −q × average (D113).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({Installation.FreshDatabase.class, SettlementTestClock.Config.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R4AcceptanceTest {

    static final String INITIAL_PASSWORD = "initial " + UUID.randomUUID();
    static final String NEW_PASSWORD = "changed " + UUID.randomUUID();
    static final String CASHIER_PIN = "5172";
    static final String MANAGER_PIN = "6283";
    static final UUID WAITER_ROLE = UUID.fromString("01900000-0000-7000-8000-000000000303");
    static final Charset CP860 = Charset.forName("IBM860");
    static final Charset WPC1252 = Charset.forName("windows-1252");
    static final Duration PRINTED = Duration.ofSeconds(20);
    static final Duration PROJECTED = Duration.ofSeconds(30);
    static final String POS = "/api/v1/pos";
    static final String BO = "/api/v1/backoffice";
    static final String INV = BO + "/inventory";
    static final String CATALOG = Installation.CATALOG;
    static final ZoneId ZONE = ZoneId.of(Installation.ZONE);

    static final TcpFakePrinter KITCHEN = open();
    static final TcpFakePrinter BAR = open();
    static final TcpFakePrinter COUNTER = open();

    @DynamicPropertySource
    static void bootstrapSettings(DynamicPropertyRegistry registry) throws IOException {
        Installation.bootstrap(registry, "Restaurante R4", INITIAL_PASSWORD);
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

    @Autowired
    SettlementTestClock clock;

    private final List<String> journey = new ArrayList<>();

    // the installation
    Browser office;
    Browser manager;
    Browser cashier;
    UUID storeA;
    UUID storeB;
    UUID managerId;
    String managerCode;
    UUID courier;
    UUID area;
    UUID tableM1;
    UUID tableM2;
    final Map<String, UUID> items = new LinkedHashMap<>();
    final Map<String, UUID> families = new LinkedHashMap<>();
    final Map<String, UUID> stock = new LinkedHashMap<>();
    UUID kitchen;
    UUID bar;
    UUID warehouseB;
    UUID member;
    UUID guest;
    UUID guestAddress;
    UUID zone;
    LocalDate day;
    long catalogVersion;
    long promotionVersion;

    // facts of the day, for step 7
    UUID orderM1;
    UUID orderDelivery;
    UUID orderM2;

    @Test
    void stageFourDayEndToEnd() throws Exception {
        try {
            setUp();
            purchaseTwoPartialReceiptsAndACostCorrection();
            reservationHappyHourMenuAndOffer();
            voidsRewardSplitPayAndClose();
            delivery();
            countDuringServiceAndTransferWithShortage();
            marginsDiscountsValuationAndDocumentsAgreeAndRebuildsAreIdentical();
        } finally {
            clock.reset();
            System.out.println("R4 acceptance journey (step -> HTTP status):\n  " + String.join("\n  ", journey));
        }
    }

    // ---- 1. setup ----

    private void setUp() throws Exception {
        clock.moveTo(nextSixPmInTheStore());
        office = new Browser("backoffice", port, journey);
        Installation.firstLogin(office, INITIAL_PASSWORD, NEW_PASSWORD);
        storeA = store("A");
        storeB = store("B");
        UUID terminal = terminal(office, storeA, "POS1");
        String cashierCode = Installation.employee(office, storeA, "CX1", "Carla", CASHIER_PIN, CASHIER_ROLE);
        managerCode = "GR1";
        managerId = id(office.post(BO + "/employees", Map.of("code", managerCode, "name", "Gil", "pin", MANAGER_PIN,
                "storeId", storeA, "roleId", MANAGER_ROLE), 201));
        manager = backOfficeUser("gil", managerId);
        courier = id(office.post(BO + "/employees", Map.of("code", "ES1", "name", "Edson", "pin", "7391",
                "storeId", storeA, "roleId", WAITER_ROLE), 201));
        area = id(office.post(BO + "/areas", Map.of("storeId", storeA, "code", "SALA", "name", "Sala"), 201));
        tableM1 = table("M1");
        tableM2 = table("M2");

        UUID kitchenPrinter = printer("COZ", KITCHEN, "CP860");
        UUID barPrinter = printer("BAR", BAR, "CP860");
        UUID counterPrinter = printer("BALCAO", COUNTER, "WPC1252");
        office.put(BO + "/production-routes", Map.of("storeId", storeA, "routes", List.of(
                Map.of("destinationCode", "COZINHA", "printerId", kitchenPrinter),
                Map.of("destinationCode", "BAR", "printerId", barPrinter))), 200, "production routes");
        office.put(BO + "/terminal-printers", Map.of("storeId", storeA, "assignments",
                List.of(Map.of("terminalId", terminal, "printerId", counterPrinter))), 200, "terminal printer");
        UUID register = id(office.command(BO + "/cash/registers", Map.of("storeId", storeA, "code", "CX1",
                "name", "Caixa 1"), 201, "register"));
        office.command(BO + "/fiscal/series", Map.of("storeId", storeA, "code", "FR", "firstNumber", 1), 201,
                "series");

        publishCatalog();
        inventorySetUp();
        promotionsSetUp();
        loyaltySetUp();
        deliverySetUp();

        cashier = posLogin(office, terminal, cashierCode, CASHIER_PIN, "cashier");
        JsonNode session = cashier.command(POS + "/cash/sessions", Map.of("registerId", register, "openingFloat",
                new BigDecimal("0.00")), 201, "open session");
        day = LocalDate.parse(session.get("businessDate").asString());
        assertThat(day).as("18:00 store time is before the 05:00 cutoff of the next morning")
                .isEqualTo(clock.instant().atZone(ZONE).toLocalDate());
        catalogVersion = cashier.get(POS + "/catalog?storeId=" + storeA, 200).get("version").asLong();
        promotionVersion = cashier.get(POS + "/promotions/current", 200).get("version").asLong();
        cashier.command(POS + "/customers/" + member + "/loyalty", null, 201, "enroll the member");
        // the welcome bonus, so the member can redeem today: +60
        office.command(BO + "/loyalty/accounts/" + member + "/adjustments", Map.of("points", 60, "reason",
                "Boas-vindas", "storeId", storeA), 201, "loyalty adjustment +60");
        assertThat(points(member)).isEqualTo(60);
    }

    /**
     * Families COMIDA, BEBIDAS, MENUS and TAXAS; Prego (kitchen) 150.00, Sumo and Cafe (bar) 60.00 and 50.00; the menu
     * "Menu prego" 180.00 with one starter (Prego) and one drink (Sumo); the delivery fee 50.00 (kind FEE). All at
     * 16 % VAT, published for store A.
     */
    private void publishCatalog() throws Exception {
        for (String family : List.of("COMIDA", "BEBIDAS", "MENUS", "TAXAS")) {
            families.put(family, id(office.post(CATALOG + "/families", Map.of("code", family, "name", family), 201)));
        }
        UUID unit = id(office.post(CATALOG + "/units", Map.of("code", "UN", "name", "Unidade"), 201));
        UUID tax = id(office.post(CATALOG + "/tax-categories", Map.of("code", "IVA16", "name", "IVA"), 201));
        office.post(CATALOG + "/tax-categories/" + tax + "/rates",
                Map.of("ratePercent", new BigDecimal("16"), "effectiveFrom", "2000-01-01T00:00:00Z"), 201);
        UUID priceList = id(office.post(CATALOG + "/price-lists", Map.of("storeId", storeA, "name", "Sala"), 201));
        String[][] dishes = {{"PREGO", "Prego no pao", "COZINHA", "150.00", "COMIDA", "STANDARD"},
                {"SUMO", "Sumo de manga", "BAR", "60.00", "BEBIDAS", "STANDARD"},
                {"CAFE", "Cafe expresso", "BAR", "50.00", "BEBIDAS", "STANDARD"},
                {"MENU", "Menu prego", null, "180.00", "MENUS", "MENU"},
                {"TAXA", "Taxa de entrega", null, "50.00", "TAXAS", "FEE"}};
        for (String[] dish : dishes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", dish[0]);
            item.put("name", dish[1]);
            item.put("familyId", families.get(dish[4]));
            item.put("unitId", unit);
            item.put("taxCategoryId", tax);
            item.put("productionDestination", dish[2]);
            item.put("soldByWeight", false);
            item.put("kind", dish[5]);
            UUID itemId = id(office.post(CATALOG + "/items", item, 201));
            office.post(CATALOG + "/price-lists/" + priceList + "/prices", Map.of("itemId", itemId,
                    "amount", new BigDecimal(dish[3])), 201);
            items.put(dish[0], itemId);
        }
        office.command(CATALOG + "/items/" + items.get("MENU") + "/menu-groups", Map.of("code", "PRATO", "name",
                "Prato", "minChoices", 1, "maxChoices", 1, "position", 1, "options", List.of(Map.of("itemId",
                        items.get("PREGO"), "quantity", BigDecimal.ONE))), 201, "menu group PRATO");
        office.command(CATALOG + "/items/" + items.get("MENU") + "/menu-groups", Map.of("code", "BEBIDA", "name",
                "Bebida", "minChoices", 1, "maxChoices", 1, "position", 2, "options", List.of(Map.of("itemId",
                        items.get("SUMO"), "quantity", BigDecimal.ONE))), 201, "menu group BEBIDA");
        UUID release = id(office.post(CATALOG + "/releases", Map.of("storeId", storeA), 201));
        office.post(CATALOG + "/releases/" + release + "/validate", null, 200);
        office.command(CATALOG + "/releases/" + release + "/publish", Map.of("note", "R4"), 200, "publish catalog");
    }

    /**
     * Kitchen (the default route) and bar (route BAR) warehouses; steak (BIFE), bread (PAO), box (CAIXA), mango (MANGA)
     * and coffee capsule (CAPSULA); recipes: a prego = 1 steak + 1 bread (+ 1 box for TAKEAWAY and DELIVERY only), a
     * juice = 1 mango, a coffee = 1 capsule; the fee family is not tracked. Store A goes live with an opening count per
     * warehouse: bread 40 @ 2.00 (80.00), boxes 20 @ 1.50 (30.00); mangos 30 @ 5.00 (150.00), capsules 100 @ 2.00
     * (200.00). Store B has one warehouse and goes live by activation.
     */
    private void inventorySetUp() throws Exception {
        kitchen = warehouse(storeA, "COZ", "Cozinha");
        bar = warehouse(storeA, "BAR", "Bar");
        warehouseB = warehouse(storeB, "ARM", "Armazem B");
        office.command(INV + "/consumption-routes", Map.of("storeId", storeA, "warehouseId", kitchen), 201,
                "default route: kitchen");
        office.command(INV + "/consumption-routes", Map.of("storeId", storeA, "destinationCode", "BAR",
                "warehouseId", bar), 201, "route BAR: bar");
        for (String[] item : new String[][] {{"BIFE", "Bife"}, {"PAO", "Pao"}, {"CAIXA", "Caixa takeaway"},
                {"MANGA", "Manga"}, {"CAPSULA", "Capsula de cafe"}}) {
            stock.put(item[0], id(office.command(INV + "/stock-items", Map.of("code", item[0], "name", item[1],
                    "baseUnit", "un", "kind", "PURCHASED"), 201, "stock item " + item[0])));
        }
        recipe("PREGO", List.of(ingredient("BIFE", "INGREDIENT", null), ingredient("PAO", "INGREDIENT", null),
                ingredient("CAIXA", "PACKAGING", List.of("TAKEAWAY", "DELIVERY"))));
        recipe("SUMO", List.of(ingredient("MANGA", "INGREDIENT", null)));
        recipe("CAFE", List.of(ingredient("CAPSULA", "INGREDIENT", null)));
        JsonNode policy = office.command(INV + "/consumption-policies", Map.of("rules", List.of(Map.of("familyId",
                families.get("TAXAS"), "tracked", false))), 201, "policy: the fee is not stock");
        office.command(INV + "/consumption-policies/" + policy.get("versionNo").asInt() + "/activate", Map.of(), 200,
                "activate the policy");

        assertThat(office.get(INV + "/status?storeId=" + storeA, 200).toString()).contains("INVENTORY_NOT_ACTIVE");
        opening(kitchen, Map.of("PAO", new String[] {"40", "2.00"}, "CAIXA", new String[] {"20", "1.50"}));
        opening(bar, Map.of("MANGA", new String[] {"30", "5.00"}, "CAPSULA", new String[] {"100", "2.00"}));
        assertThat(office.get(INV + "/status?storeId=" + storeA, 200).get("activeFrom").isNull()).isFalse();
        assertBalance(kitchen, "PAO", "40", "80.0000");
        assertBalance(kitchen, "CAIXA", "20", "30.0000");
        assertBalance(bar, "MANGA", "30", "150.0000");
        assertBalance(bar, "CAPSULA", "100", "200.0000");
        office.command(INV + "/activations", Map.of("storeId", storeB), 201, "store B goes live");
    }

    /** Happy hour: 20 % off the juice, every day 17:00–19:00 store time; discount limits 10 % alone, 50 % approved. */
    private void promotionsSetUp() throws Exception {
        String promotions = BO + "/promotions";
        JsonNode happyHour = office.command(promotions, Map.of("code", "HH", "name", "Happy hour", "kind",
                "PERCENT_OFF", "percent", new BigDecimal("20"), "priority", 1), 201, "promotion HH");
        UUID hh = id(happyHour);
        JsonNode scoped = office.put(promotions + "/" + hh + "/scope", Map.of("itemIds", List.of(items.get("SUMO")),
                "variantIds", List.of(), "familyIds", List.of(), "version", happyHour.get("version").asLong()), 200,
                "HH scope: the juice");
        JsonNode windowed = office.put(promotions + "/" + hh + "/windows", Map.of("windows", List.of(Map.of(
                "daysOfWeek", List.of(1, 2, 3, 4, 5, 6, 7), "start", "17:00", "end", "19:00")), "version",
                scoped.get("version").asLong()), 200, "HH window 17:00-19:00");
        office.put(promotions + "/" + hh + "/stores", Map.of("storeIds", List.of(storeA), "version",
                windowed.get("version").asLong()), 200, "HH in store A");
        office.put(promotions + "/discount-limits/" + storeA, Map.of("freeLimitPercent", new BigDecimal("10"),
                "maxPercent", new BigDecimal("50")), 200, "discount limits 10/50");
        UUID release = id(office.command(promotions + "/releases", Map.of("storeId", storeA), 201,
                "promotion release"));
        office.command(promotions + "/releases/" + release + "/validate", Map.of(), 200, "validate promotions");
        office.command(promotions + "/releases/" + release + "/publish", Map.of(), 200, "publish promotions");
    }

    /** One point per 10.00 of eligible gross; reward CAFE_GRATIS: a free coffee for 50 points; a member customer. */
    private void loyaltySetUp() throws Exception {
        String programs = BO + "/loyalty/program-versions";
        UUID program = id(office.command(programs, Map.of("spendUnit", new BigDecimal("10.00"), "pointsPerUnit", 1,
                "itemIds", List.of(), "familyIds", List.of()), 201, "loyalty program"));
        Map<String, Object> reward = new LinkedHashMap<>();
        reward.put("code", "CAFE_GRATIS");
        reward.put("name", "Cafe gratis");
        reward.put("pointsCost", 50);
        reward.put("effect", "FREE_UNIT");
        reward.put("amount", null);
        reward.put("itemId", items.get("CAFE"));
        reward.put("familyId", null);
        office.command(programs + "/" + program + "/rewards", reward, 201, "reward CAFE_GRATIS");
        long version = office.get(programs + "/" + program, 200).get("version").asLong();
        office.command(programs + "/" + program + "/publish", Map.of("version", version), 200, "publish program");
        member = id(office.command(BO + "/customers", Map.of("name", "Marta Sitoe", "phone", "841112222"), 201,
                "member customer"));
    }

    /** Zone CENTRO whose fee is the TAXA item; a delivery customer with an address. */
    private void deliverySetUp() throws Exception {
        zone = id(office.command(BO + "/delivery-zones", Map.of("storeId", storeA, "code", "CENTRO", "name",
                "Centro", "feeItemId", items.get("TAXA")), 201, "delivery zone"));
        guest = id(office.command(BO + "/customers", Map.of("name", "Rui Tembe", "phone", "842345678"), 201,
                "delivery customer"));
        guestAddress = id(office.command(BO + "/customers/" + guest + "/addresses", Map.of("kind", "DELIVERY",
                "line1", "Av. Julius Nyerere 100", "city", "Maputo", "notes", "portao azul"), 201, "address"));
    }

    // ---- 2. purchasing ----

    private void purchaseTwoPartialReceiptsAndACostCorrection() throws Exception {
        UUID supplier = id(office.command(INV + "/suppliers", Map.of("code", "TALHO", "name", "Talho Central"), 201,
                "supplier"));
        JsonNode created = office.command(INV + "/purchase-orders", Map.of("warehouseId", kitchen, "supplierId",
                supplier, "number", "PO-1", "lines", List.of(Map.of("stockItemId", stock.get("BIFE"), "quantity",
                        new BigDecimal("20"), "unitCost", new BigDecimal("10.00")))), 201, "purchase order 20 steaks");
        UUID po = id(created);
        UUID poLine = id(created.get("lines").get(0));
        office.command(INV + "/purchase-orders/" + po + "/submit", Map.of(), 200, "submit the order");
        assertThat(movementsOf(kitchen, "BIFE")).as("a purchase order alone never moves stock").isZero();

        UUID first = receipt(supplier, po, poLine, "10", "10.00", "FT-101");
        office.command(INV + "/receipts/" + first + "/confirm", Map.of(), 200, "confirm receipt 1");
        assertThat(office.get(INV + "/purchase-orders/" + po, 200).get("progress").asString()).isEqualTo("PARTIAL");
        // 10 @ 10.00 = 100.00
        assertBalance(kitchen, "BIFE", "10", "100.0000");

        UUID second = receipt(supplier, po, poLine, "10", "12.00", "FT-102");
        JsonNode confirmed = office.command(INV + "/receipts/" + second + "/confirm", Map.of(), 200,
                "confirm receipt 2");
        assertThat(office.get(INV + "/purchase-orders/" + po, 200).get("progress").asString()).isEqualTo("FULL");
        // 100.00 + 10 @ 12.00 = 220.00 for 20: average 11.00 (A46)
        assertBalance(kitchen, "BIFE", "20", "220.0000");
        assertThat(averageCost(kitchen, "BIFE")).isEqualByComparingTo("11.00");

        // the supplier's invoice says 13.00 for the second delivery: nothing of it was used yet, so its whole share
        // (10) is revalued: +10 × (13.00 − 12.00) = +10.00, and no cost variance is left: 230.00 / 20 = 11.50
        UUID secondLine = id(confirmed.get("lines").get(0));
        JsonNode correction = office.command(INV + "/receipts/" + second + "/lines/" + secondLine
                + "/cost-corrections", Map.of("newUnitCost", new BigDecimal("13.00"), "reason", "Fatura FT-102"),
                201, "cost correction 12.00 -> 13.00");
        assertThat(correction.get("revaluationValue").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(correction.get("varianceValue").decimalValue()).isEqualByComparingTo("0");
        assertBalance(kitchen, "BIFE", "20", "230.0000");
        assertThat(averageCost(kitchen, "BIFE")).isEqualByComparingTo("11.50");
        assertThat(count("SELECT count(*) FROM inventory.stock_movement WHERE source_id IN (?, ?) "
                + "AND type = 'RECEIPT'", first, second)).isEqualTo(2);
    }

    // ---- 3. reservation, happy hour, menu, offer ----

    private void reservationHappyHourMenuAndOffer() throws Exception {
        Instant startsAt = clock.instant().plus(Duration.ofMinutes(30)).truncatedTo(java.time.temporal.ChronoUnit
                .SECONDS);
        UUID reservation = id(cashier.command(POS + "/reservations", Map.of("startsAt", startsAt.toString(),
                "durationMinutes", 90, "partySize", 2, "contactName", "Marta Sitoe", "contactPhone", "841112222",
                "customerId", member, "tableIds", List.of(tableM1)), 201, "reservation M1 in 30 min"));
        JsonNode m1 = tableOnMap("M1");
        assertThat(m1.get("reserved").asBoolean()).as("within the 120 min window (A50)").isTrue();
        assertThat(Instant.parse(m1.get("nextReservationAt").asString())).isEqualTo(startsAt);
        assertThat(m1.get("status").asString()).isEqualTo("FREE");

        JsonNode opened = cashier.command(POS + "/tables/" + tableM1 + "/open", Map.of("guests", 2,
                "reservationId", reservation), 201, "seat the reservation at M1");
        orderM1 = id(opened);
        String header = "Mesa M1 - Pedido #" + opened.get("orderNumber").asLong();
        JsonNode seated = reservationOf(reservation);
        assertThat(seated.get("status").asString()).isEqualTo("SEATED");
        assertThat(seated.get("occupationId").isNull()).isFalse();
        cashier.command(POS + "/orders/" + orderM1 + "/customer", Map.of("orderVersion", version(orderM1),
                "customerId", member), 200, "the member on M1");

        UUID prego = line(orderM1, "PREGO", "2");
        UUID sumo = line(orderM1, "SUMO", "2");
        UUID cafe = line(orderM1, "CAFE", "3");
        UUID menu = id(cashier.command(POS + "/orders/" + orderM1 + "/lines", Map.of("itemId", items.get("MENU"),
                "quantity", BigDecimal.ONE, "components", List.of(
                        Map.of("groupCode", "PRATO", "itemId", items.get("PREGO")),
                        Map.of("groupCode", "BEBIDA", "itemId", items.get("SUMO")))), 201, "draft MENU"));
        // the preview: 2 × 150.00 + 2 × 48.00 (60.00 − 20 % per unit, 18:00 is in the happy hour) + 3 × 50.00
        // + 180.00 = 726.00
        JsonNode draft = cashier.get(POS + "/orders/" + orderM1, 200);
        assertThat(money(lineOf(draft, sumo).get("unitPrice"))).isEqualByComparingTo("48.00");
        assertThat(StreamSupport.stream(draft.get("lines").spliterator(), false).map(l -> money(l.get("gross")))
                .reduce(BigDecimal.ZERO, BigDecimal::add)).as("Σ preview gross").isEqualByComparingTo("726.00");
        assertThat(money(draft.get("due"))).as("drafts are not due yet").isEqualByComparingTo("0.00");
        JsonNode round = cashier.command(POS + "/orders/" + orderM1 + "/batches", confirmation(orderM1,
                List.of(prego, sumo, cafe, menu), "726.00"), 201, "M1 round 1 (happy hour)");
        UUID batch = id(round);

        JsonNode order = cashier.get(POS + "/orders/" + orderM1, 200);
        JsonNode juice = lineOf(order, sumo);
        assertThat(money(juice.get("unitPrice"))).isEqualByComparingTo("48.00");
        assertThat(money(juice.get("listUnitPrice"))).isEqualByComparingTo("60.00");
        assertThat(juice.get("origin").asString()).isEqualTo("PROMOTION");
        assertThat(juice.get("promotionCode").asString()).isEqualTo("HH");
        JsonNode menuLine = lineOf(order, menu);
        assertThat(money(menuLine.get("gross"))).as("one billed line at the menu price").isEqualByComparingTo(
                "180.00");
        assertThat(menuLine.get("components")).hasSize(2);
        assertThat(money(order.get("due"))).isEqualByComparingTo("726.00");

        // A43: the menu's starter prints in the kitchen with the pregos, its drink at the bar with the juices
        String kitchenTicket = awaitPrinted(KITCHEN, CP860, header, s -> s.contains("Ronda 1"));
        assertThat(kitchenTicket).contains("2 x Prego no pao").contains("Menu prego");
        String barTicket = awaitPrinted(BAR, CP860, header, s -> s.contains("Ronda 1"));
        assertThat(barTicket).contains("2 x Sumo de manga").contains("3 x Cafe expresso").contains("Menu prego");

        // A44: the round is consumed from the routed warehouses at the recipes in force (dine-in: no box):
        // prego line 2 steaks + 2 breads, menu starter 1 + 1 (kitchen); juice line 2 mangos, menu drink 1, 3 capsules
        // (bar): 7 movements, one per line or component and stock item
        Awaitility.await().atMost(PROJECTED).until(() -> saleMovementsOfBatch(batch) == 7);
        // steak: 2 × 11.50 + 1 × 11.50 = 34.50 out of 230.00 → 17 left, 195.50
        assertBalance(kitchen, "BIFE", "17", "195.5000");
        // bread: 3 × 2.00 = 6.00 out of 80.00 → 37, 74.00; boxes untouched (dine-in)
        assertBalance(kitchen, "PAO", "37", "74.0000");
        assertBalance(kitchen, "CAIXA", "20", "30.0000");
        // mango: 3 × 5.00 → 27, 135.00; capsules: 3 × 2.00 → 97, 194.00
        assertBalance(bar, "MANGA", "27", "135.0000");
        assertBalance(bar, "CAPSULA", "97", "194.0000");
        assertThat(count("SELECT count(*) FROM inventory.consumption_exception WHERE store_id = ?", storeA))
                .as("every sold unit had a recipe and a route").isZero();

        // A40: an offer always needs an approver; one of the three coffees is offered, which splits the line
        Map<String, Object> offer = Map.of("orderVersion", version(orderM1), "lines", List.of(Map.of("lineId", cafe,
                "quantity", BigDecimal.ONE)), "kind", "OFFER", "reason", "Cliente habitual");
        cashier.command(POS + "/orders/" + orderM1 + "/discounts", offer, 403, "offer without an approver");
        cashier.post(POS + "/orders/" + orderM1 + "/discounts", offer, 200, Csrf.SEND, approver(),
                "offer 1 of 3 coffees (approver)");
        JsonNode afterOffer = cashier.get(POS + "/orders/" + orderM1, 200);
        assertThat(lineOf(afterOffer, cafe).get("netQuantity").decimalValue()).isEqualByComparingTo("2");
        List<JsonNode> offered = linesWhere(afterOffer, l -> l.get("itemId").asString().equals(items.get("CAFE")
                .toString()) && !l.get("id").asString().equals(cafe.toString()));
        assertThat(offered).hasSize(1);
        assertThat(offered.getFirst().get("batchId").isNull()).as("a split copy has no batch").isTrue();
        assertThat(money(offered.getFirst().get("unitPrice"))).isEqualByComparingTo("0.00");
        assertThat(offered.getFirst().get("origin").asString()).isEqualTo("OFFER");
        // 726.00 − 50.00 = 676.00
        assertThat(money(afterOffer.get("due"))).isEqualByComparingTo("676.00");
        // production and stock see the coffees once: no new ticket line, no new movement
        assertThat(saleMovementsOfOrder(orderM1)).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM production.production_ticket WHERE order_id = ?", orderM1))
                .as("one kitchen and one bar ticket").isEqualTo(2);
    }

    // ---- 4. voids, reward, split, pay, close ----

    private void voidsRewardSplitPayAndClose() throws Exception {
        JsonNode order = cashier.get(POS + "/orders/" + orderM1, 200);
        UUID prego = batchLineOf(order, "PREGO");
        UUID sumo = batchLineOf(order, "SUMO");
        UUID cafe = batchLineOf(order, "CAFE");
        UUID menu = batchLineOf(order, "MENU");

        // a prego typed by mistake, nothing cooked: the stock comes back at the original cost (A45)
        JsonNode notWasted = cashier.post(POS + "/orders/" + orderM1 + "/voids", Map.of("orderVersion",
                version(orderM1), "lineId", prego, "quantity", BigDecimal.ONE, "reason", "Engano", "wasted", false),
                201, Csrf.SEND, approver(), "void 1 prego, not wasted");
        UUID voidNotWasted = id(notWasted);
        // a juice spilt: wasted, so the stock stays used and its cost moves from sale to waste
        JsonNode wasted = cashier.post(POS + "/orders/" + orderM1 + "/voids", Map.of("orderVersion",
                version(orderM1), "lineId", sumo, "quantity", BigDecimal.ONE, "reason", "Entornado", "wasted", true),
                201, Csrf.SEND, approver(), "void 1 juice, wasted");
        UUID voidWasted = id(wasted);
        Awaitility.await().atMost(PROJECTED).until(() -> count("SELECT count(*) FROM inventory.stock_movement "
                + "WHERE type = 'CONSUMPTION_REVERSAL' AND store_id = ?", storeA) == 2);
        Awaitility.await().atMost(PROJECTED).until(() -> count("SELECT count(*) FROM inventory.waste_record "
                + "WHERE void_id = ?", voidWasted) == 1);
        // reversal of 1 prego: +1 steak at 11.50, +1 bread at 2.00
        assertThat(decimal("SELECT sum(value) FROM inventory.stock_movement WHERE type = 'CONSUMPTION_REVERSAL' "
                + "AND store_id = ?", storeA)).isEqualByComparingTo("13.50");
        assertThat(count("SELECT count(*) FROM inventory.stock_movement WHERE source_id = ?", voidNotWasted))
                .isEqualTo(2);
        assertBalance(kitchen, "BIFE", "18", "207.0000");
        assertBalance(kitchen, "PAO", "38", "76.0000");
        // the wasted juice posts nothing (D117 amended): 1 mango at 5.00 reclassified to waste
        assertThat(count("SELECT count(*) FROM inventory.stock_movement WHERE source_id = ?", voidWasted)).isZero();
        assertThat(decimal("SELECT sum(value) FROM inventory.waste_record WHERE void_id = ?", voidWasted))
                .isEqualByComparingTo("5.00");
        assertBalance(bar, "MANGA", "27", "135.0000");

        // the member redeems a free coffee: 50 of their 60 points; the paid coffee line splits again
        cashier.command(POS + "/orders/" + orderM1 + "/discounts", Map.of("orderVersion", version(orderM1), "lines",
                List.of(Map.of("lineId", cafe, "quantity", BigDecimal.ONE)), "kind", "REWARD", "rewardCode",
                "CAFE_GRATIS", "reason", "Premio"), 200, "redeem CAFE_GRATIS");
        assertThat(points(member)).as("60 − 50").isEqualTo(10);
        assertThat(count("SELECT count(*) FROM promotions.loyalty_point_entry WHERE customer_id = ? "
                + "AND kind = 'REDEEM' AND points = -50", member)).isEqualTo(1);

        // the bill: prego 1 × 150.00 + juice 1 × 48.00 + menu 180.00 + coffee 1 × 50.00 (+ offered and rewarded
        // coffees at 0.00) = 428.00
        JsonNode bill = settlement(orderM1);
        assertThat(money(bill.get("due"))).isEqualByComparingTo("428.00");
        assertThat(saleMovementsOfOrder(orderM1)).as("discounts change no stock").isEqualTo(7);

        // split: diner A takes the prego and the menu (150.00 + 180.00 = 330.00), pays cash, gets their document
        JsonNode dinerA = cashier.command(POS + "/orders/" + orderM1 + "/partitions", Map.of("orderVersion",
                version(orderM1), "kind", "ITEMS", "label", "Diner A", "lines", List.of(
                        Map.of("lineId", prego, "quantity", BigDecimal.ONE),
                        Map.of("lineId", menu, "quantity", BigDecimal.ONE))), 201, "partition A");
        UUID partitionA = id(dinerA);
        assertThat(money(dinerA.get("due"))).isEqualByComparingTo("330.00");
        pay(orderM1, partitionA, "330.00", "A pays 330.00 in cash");
        JsonNode documentA = cashier.command(POS + "/partitions/" + partitionA + "/documents", Map.of(), 201,
                "partition A document");
        assertThat(money(documentA.get("totalGross"))).isEqualByComparingTo("330.00");
        // tax 20.69 + 24.83 = 45.52
        assertThat(documentTax(id(documentA))).isEqualByComparingTo("45.52");

        // the rest: 428.00 − 330.00 = 98.00 (juice 48.00 + coffee 50.00 + two coffees at 0.00), then the close
        pay(orderM1, null, "98.00", "the rest, 98.00, in cash");
        JsonNode closed = cashier.command(POS + "/orders/" + orderM1 + "/close", Map.of("orderVersion",
                settlement(orderM1).get("orderVersion").asLong()), 200, "close M1");
        assertThat(closed.get("lifecycle").asString()).isEqualTo("CLOSED");
        UUID residual = UUID.fromString(closed.get("documentId").asString());
        assertThat(decimal("SELECT total_gross FROM fiscal.fiscal_document WHERE id = ?", residual))
                .isEqualByComparingTo("98.00");
        // tax 6.62 + 6.90 + 0.00 + 0.00 = 13.52
        assertThat(documentTax(residual)).isEqualByComparingTo("13.52");
        assertThat(count("SELECT count(*) FROM fiscal.fiscal_document_line l JOIN fiscal.fiscal_document d "
                + "ON d.id = l.document_id WHERE d.id = ? AND l.gross = 0", residual))
                .as("the offered and the rewarded coffee, at 0.00").isEqualTo(2);

        // A42: one EARN at close: eligible = 428.00 (after discounts; the rewarded coffee is excluded and the
        // offered one is 0.00) → ⌊428.00 ÷ 10.00⌋ × 1 = 42; balance 10 + 42 = 52 = Σ entries (+60 −50 +42)
        assertThat(count("SELECT count(*) FROM promotions.loyalty_point_entry WHERE kind = 'EARN' AND order_id = ?",
                orderM1)).isEqualTo(1);
        assertThat(count("SELECT points FROM promotions.loyalty_point_entry WHERE kind = 'EARN' AND order_id = ?",
                orderM1)).isEqualTo(42);
        assertThat(points(member)).isEqualTo(52);
        assertThat(count("SELECT sum(points) FROM promotions.loyalty_point_entry WHERE customer_id = ?", member))
                .isEqualTo(52);
        // a retried close changes nothing: same state, no second EARN
        cashier.command(POS + "/orders/" + orderM1 + "/close", Map.of("orderVersion",
                settlement(orderM1).get("orderVersion").asLong()), 200, "close M1 again");
        assertThat(count("SELECT count(*) FROM promotions.loyalty_point_entry WHERE kind = 'EARN' AND order_id = ?",
                orderM1)).isEqualTo(1);
    }

    // ---- 5. delivery ----

    private void delivery() throws Exception {
        JsonNode opened = cashier.command(POS + "/orders", Map.of("mode", "DELIVERY", "delivery", Map.of(
                "customerId", guest, "addressId", guestAddress, "zoneId", zone)), 201, "open a delivery");
        orderDelivery = id(opened);
        long number = opened.get("orderNumber").asLong();
        List<JsonNode> fee = linesWhere(opened, l -> l.get("itemId").asString().equals(items.get("TAXA").toString()));
        assertThat(fee).as("the zone's fee item as an automatic draft (D128)").hasSize(1);
        assertThat(fee.getFirst().get("automatic").asBoolean()).isTrue();
        assertThat(money(fee.getFirst().get("unitPrice"))).isEqualByComparingTo("50.00");
        UUID feeLine = id(fee.getFirst());
        UUID prego = line(orderDelivery, "PREGO", "1");
        // 150.00 + 50.00 = 200.00
        UUID batch = id(cashier.command(POS + "/orders/" + orderDelivery + "/batches", confirmation(orderDelivery,
                List.of(feeLine, prego), "200.00"), 201, "delivery round"));
        assertThat(lineOf(cashier.get(POS + "/orders/" + orderDelivery, 200), feeLine).get("kind").asString())
                .isEqualTo("FEE");
        String kitchenTicket = awaitPrinted(KITCHEN, CP860, "ENTREGA #" + number, s -> true);
        assertThat(kitchenTicket).contains("1 x Prego no pao").doesNotContain("Taxa de entrega");
        // takeaway packaging applies: steak + bread + box (3 movements); the fee is untracked (no exception)
        Awaitility.await().atMost(PROJECTED).until(() -> saleMovementsOfBatch(batch) == 3);
        assertThat(count("SELECT count(*) FROM inventory.consumption_exception WHERE store_id = ?", storeA)).isZero();
        // steak: 207.00 − 11.50 → 17, 195.50; bread: 76.00 − 2.00 → 37, 74.00; box: −round4(30 × 1 ÷ 20) = −1.50
        assertBalance(kitchen, "BIFE", "17", "195.5000");
        assertBalance(kitchen, "PAO", "37", "74.0000");
        assertBalance(kitchen, "CAIXA", "19", "28.5000");

        long deliveryVersion = deliveryOf(orderDelivery).get("version").asLong();
        JsonNode dispatched = cashier.command(POS + "/deliveries/" + orderDelivery + "/dispatch", Map.of("version",
                deliveryVersion, "courierEmployeeId", courier), 200, "dispatch with Edson");
        assertThat(dispatched.get("status").asString()).isEqualTo("OUT_FOR_DELIVERY");
        String slip = awaitPrinted(COUNTER, WPC1252, "GUIA DE ENTREGA", s -> s.contains("#" + number));
        assertThat(slip).contains("Rui Tembe").contains("Av. Julius Nyerere 100").contains("200.00");

        // A51: the close is refused before the delivery is DELIVERED, before any document
        JsonNode refused = cashier.command(POS + "/orders/" + orderDelivery + "/close", Map.of("orderVersion",
                settlement(orderDelivery).get("orderVersion").asLong()), 422, "close while out for delivery");
        assertThat(refused.get("code").asString()).isEqualTo("DELIVERY_NOT_DELIVERED");
        assertThat(count("SELECT count(*) FROM fiscal.fiscal_document WHERE order_id = ?", orderDelivery)).isZero();
        JsonNode open = openOrder(orderDelivery);
        assertThat(open.get("mode").asString()).isEqualTo("DELIVERY");
        assertThat(open.get("delivery").get("status").asString()).isEqualTo("OUT_FOR_DELIVERY");

        JsonNode delivered = cashier.command(POS + "/deliveries/" + orderDelivery + "/delivered", Map.of("version",
                dispatched.get("version").asLong()), 200, "delivered");
        assertThat(delivered.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(delivered.get("history")).hasSizeGreaterThanOrEqualTo(3);
        // the courier is back with the cash: the cashier records it in her own session
        pay(orderDelivery, null, "200.00", "courier's cash, 200.00");
        JsonNode closed = cashier.command(POS + "/orders/" + orderDelivery + "/close", Map.of("orderVersion",
                settlement(orderDelivery).get("orderVersion").asLong()), 200, "close the delivery");
        UUID document = UUID.fromString(closed.get("documentId").asString());
        assertThat(decimal("SELECT total_gross FROM fiscal.fiscal_document WHERE id = ?", document))
                .isEqualByComparingTo("200.00");
        // tax 20.69 (prego) + 6.90 (fee 50.00 → 43.10 + 6.90) = 27.59
        assertThat(documentTax(document)).isEqualByComparingTo("27.59");
        assertThat(count("SELECT count(*) FROM promotions.loyalty_point_entry WHERE order_id = ?", orderDelivery))
                .as("not a member: no points").isZero();
    }

    // ---- 6. a count during service; a transfer between stores ----

    private void countDuringServiceAndTransferWithShortage() throws Exception {
        UUID count = id(office.command(INV + "/counts", Map.of("warehouseId", bar, "kind", "REGULAR", "scope",
                "LIST", "stockItemIds", List.of(stock.get("CAPSULA"))), 201, "bar count (capsules)"));
        office.command(INV + "/counts/" + count + "/start", Map.of(), 200, "start the count");
        Instant countedAt = clock.instant();
        // on the shelf at the cut-off: 96, one fewer than the book's 97
        office.put(INV + "/counts/" + count + "/lines/" + stock.get("CAPSULA"), Map.of("countedQuantity",
                new BigDecimal("96"), "countedAt", countedAt.toString()), 200, "count 96 capsules");

        // a coffee sold after the cut-off, during the count: 50.00 at M2
        JsonNode opened = cashier.command(POS + "/tables/" + tableM2 + "/open", Map.of("guests", 1), 201, "open M2");
        orderM2 = id(opened);
        UUID cafe = line(orderM2, "CAFE", "1");
        UUID batch = id(cashier.command(POS + "/orders/" + orderM2 + "/batches", confirmation(orderM2, List.of(cafe),
                "50.00"), 201, "M2 round"));
        Awaitility.await().atMost(PROJECTED).until(() -> saleMovementsOfBatch(batch) == 1);
        // 194.00 − 2.00 → 96, 192.00
        assertBalance(bar, "CAPSULA", "96", "192.0000");
        pay(orderM2, null, "50.00", "M2 pays 50.00");
        cashier.command(POS + "/orders/" + orderM2 + "/close", Map.of("orderVersion", settlement(orderM2)
                .get("orderVersion").asLong()), 200, "close M2");

        office.command(INV + "/counts/" + count + "/submit", Map.of(), 200, "submit the count");
        office.command(INV + "/counts/" + count + "/approve", Map.of(), 422, "self-approval refused");
        JsonNode approved = manager.command(INV + "/counts/" + count + "/approve", Map.of(), 200,
                "the manager approves");
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        // A47: expected = Σ movements up to the cut-off = 97 (the later coffee is not counted), counted 96 → −1
        JsonNode countLine = approved.get("lines").get(0);
        assertThat(countLine.get("expectedQuantity").decimalValue()).isEqualByComparingTo("97");
        assertThat(countLine.get("difference").decimalValue()).isEqualByComparingTo("-1");
        // one COUNT_ADJUSTMENT of −1 at the average 2.00 = −2.00 → 95, 190.00
        assertThat(decimal("SELECT sum(value) FROM inventory.stock_movement WHERE type = 'COUNT_ADJUSTMENT' "
                + "AND warehouse_id = ?", bar)).isEqualByComparingTo("-2.00");
        assertBalance(bar, "CAPSULA", "95", "190.0000");

        // A48: 5 steaks to store B; 4 arrive
        UUID transfer = id(office.command(INV + "/transfers", Map.of("fromWarehouseId", kitchen, "toWarehouseId",
                warehouseB, "lines", List.of(Map.of("stockItemId", stock.get("BIFE"), "quantity",
                        new BigDecimal("5")))), 201, "transfer 5 steaks to B"));
        JsonNode shipped = office.command(INV + "/transfers/" + transfer + "/ship", Map.of(), 200, "ship");
        assertThat(shipped.get("status").asString()).isEqualTo("SHIPPED");
        // −round4(195.50 × 5 ÷ 17) = −57.50 → 12, 138.00; nothing in B while in transit
        assertBalance(kitchen, "BIFE", "12", "138.0000");
        assertThat(balanceRow(warehouseB, "BIFE")).isNull();
        JsonNode received = office.command(INV + "/transfers/" + transfer + "/receive", Map.of("lines", List.of(Map.of(
                "stockItemId", stock.get("BIFE"), "quantityReceived", new BigDecimal("4")))), 200, "receive 4 in B");
        assertThat(received.get("status").asString()).isEqualTo("RECEIVED");
        assertThat(received.get("lines").get(0).get("shortfall").decimalValue()).isEqualByComparingTo("1");
        // TRANSFER_IN carries the OUT's 57.50 for 5, the shortage takes 1 × 11.50 back out → 4, 46.00
        assertThat(movementLines(warehouseB, "BIFE")).containsExactly("TRANSFER_IN|5.000000|57.5000",
                "TRANSFER_SHORTAGE|-1.000000|-11.5000");
        assertBalance(warehouseB, "BIFE", "4", "46.0000");
    }

    // ---- 7. reports agree with the facts; rebuilds are identical ----

    private void marginsDiscountsValuationAndDocumentsAgreeAndRebuildsAreIdentical() throws Exception {
        String range = "?storeId=" + storeA + "&from=" + day + "&to=" + day;
        JsonNode margins = awaitFresh(BO + "/reports/margins" + range + "&groupBy=item");
        Map<String, String[]> byItem = rows(margins, "netSales", "costOfSales", "waste", "costVariance", "margin");
        // net sales are the documents' nets (see the class comment); cost of sales = sale consumption − reversals −
        // wasted voids' shares, by the item of the round line (a menu's components are costs of the menu):
        //   prego:  M1 129.31 + delivery 129.31 = 258.62; cost: line 2 × (11.50 + 2.00) = 27.00, void −13.50,
        //           delivery 11.50 + 2.00 + 1.50 = 15.00 → 28.50; margin 230.12
        //   menu:   155.17; starter 11.50 + 2.00, drink 5.00 → 18.50; margin 136.67
        //   juice:  41.38; line 2 × 5.00 − the wasted one's 5.00 = 5.00, waste 5.00; margin 36.38
        //   coffee: M1 43.10 (+ 0.00 + 0.00) + M2 43.10 = 86.20; 3 × 2.00 + 1 × 2.00 = 8.00; margin 78.20
        //   fee:    43.10; untracked → 0.00; margin 43.10
        assertThat(byItem).containsOnlyKeys(items.get("PREGO").toString(), items.get("MENU").toString(),
                items.get("SUMO").toString(), items.get("CAFE").toString(), items.get("TAXA").toString());
        assertThat(byItem.get(items.get("PREGO").toString())).containsExactly("258.62", "28.50", "0.00", "0.00",
                "230.12");
        assertThat(byItem.get(items.get("MENU").toString())).containsExactly("155.17", "18.50", "0.00", "0.00",
                "136.67");
        assertThat(byItem.get(items.get("SUMO").toString())).containsExactly("41.38", "5.00", "5.00", "0.00",
                "36.38");
        assertThat(byItem.get(items.get("CAFE").toString())).containsExactly("86.20", "8.00", "0.00", "0.00",
                "78.20");
        assertThat(byItem.get(items.get("TAXA").toString())).containsExactly("43.10", "0.00", "0.00", "0.00",
                "43.10");
        // totals: net 584.47 = 678.00 gross − 93.53 tax (45.52 + 13.52 + 27.59 + 6.90); cost 60.00 = sale
        // consumption 78.50 (61.50 + 15.00 + 2.00) − reversal 13.50 − waste 5.00; margin 524.47, 89.73 %
        JsonNode totals = margins.get("totals");
        assertThat(money(totals.get("netSales"))).isEqualByComparingTo("584.47");
        assertThat(money(totals.get("costOfSales"))).isEqualByComparingTo("60.00");
        assertThat(money(totals.get("waste"))).isEqualByComparingTo("5.00");
        assertThat(money(totals.get("margin"))).isEqualByComparingTo("524.47");
        assertThat(totals.get("marginPercent").decimalValue()).isEqualByComparingTo("89.73");

        // the documents agree: Σ document line net per item = the margins' net sales; Σ gross = 678.00
        Map<String, BigDecimal> documentNet = perKey("""
                SELECT o.item_id::text AS k, sum(l.net) AS v FROM fiscal.fiscal_document_line l
                JOIN fiscal.fiscal_document d ON d.id = l.document_id
                JOIN orders.order_line o ON o.id = l.source_line_id
                WHERE d.store_id = ? AND d.business_date = ? AND d.status = 'ISSUED' GROUP BY 1""", storeA, day);
        byItem.forEach((item, row) -> assertThat(documentNet.get(item)).as(item).isEqualByComparingTo(row[0]));
        assertThat(decimal("SELECT sum(total_gross) FROM fiscal.fiscal_document WHERE store_id = ? "
                + "AND business_date = ? AND status = 'ISSUED'", storeA, day)).isEqualByComparingTo("678.00");
        JsonNode sales = awaitFresh(BO + "/reports/sales" + range + "&groupBy=item");
        assertThat(money(sales.get("totals").get("gross"))).isEqualByComparingTo("678.00");
        assertThat(money(sales.get("totals").get("net"))).isEqualByComparingTo("584.47");

        // discounts and offers by origin (A52): the happy hour on the 2 juices of the round, 2 × (60.00 − 48.00)
        // = 24.00 as recorded (the later void does not rewrite it); the offered coffee 50.00, whose cost is
        // 1 × 6.00 ÷ 3 = 2.00 (its round line's consumption per unit); the rewarded coffee 50.00
        JsonNode discounts = awaitFresh(BO + "/reports/discounts" + range + "&groupBy=origin");
        Map<String, String[]> byOrigin = rows(discounts, "lines", "quantity", "discount", "offerCost");
        assertThat(byOrigin).containsOnlyKeys("PROMOTION", "OFFER", "LOYALTY");
        assertThat(byOrigin.get("PROMOTION")).containsExactly("1", "2", "24.00", "0.00");
        assertThat(byOrigin.get("OFFER")).containsExactly("1", "1", "50.00", "2.00");
        assertThat(byOrigin.get("LOYALTY")).as("a reward is no offer").containsExactly("1", "1", "50.00", "0.00");
        assertThat(money(discounts.get("totals").get("discount"))).isEqualByComparingTo("124.00");
        JsonNode byApprover = awaitFresh(BO + "/reports/discounts" + range + "&groupBy=approver");
        assertThat(rows(byApprover, "discount").get(managerId.toString())).as("only the offer was approved")
                .containsExactly("50.00");

        // stock valuation = Σ balances = Σ movements (A49, A52): store A
        //   steak 12 / 138.00, bread 37 / 74.00, boxes 19 / 28.50, mangos 27 / 135.00, capsules 95 / 190.00
        //   = 565.50; in = openings 460.00 + receipts 220.00 + revaluation 10.00 = 690.00; out = sale 78.50 −
        //   reversals 13.50 + count 2.00 + transfer 57.50 = 124.50; 690.00 − 124.50 = 565.50
        JsonNode valuation = office.get(INV + "/reports/valuation?storeId=" + storeA, 200);
        assertThat(valuation.get("totalValue").decimalValue()).isEqualByComparingTo("565.50");
        assertThat(decimal("SELECT sum(value) FROM inventory.stock_movement WHERE store_id = ?", storeA))
                .isEqualByComparingTo("565.50");
        assertThat(decimal("SELECT sum(b.value) FROM inventory.stock_balance b JOIN inventory.warehouse w "
                + "ON w.id = b.warehouse_id WHERE w.store_id = ?", storeA)).isEqualByComparingTo("565.50");
        assertThat(office.get(INV + "/reports/valuation?storeId=" + storeB, 200).get("totalValue").decimalValue())
                .isEqualByComparingTo("46.00");

        // the inventory balance rebuild replays the ledger and corrects nothing
        List<String> balancesBefore = balances();
        for (UUID warehouse : List.of(kitchen, bar, warehouseB)) {
            JsonNode rebuilt = office.command(INV + "/balances/rebuild", Map.of("warehouseId", warehouse), 200,
                    "rebuild balances");
            assertThat(rebuilt.get("corrected").asInt()).isZero();
        }
        assertThat(balances()).isEqualTo(balancesBefore);
        assertThat(office.get(INV + "/reports/valuation?storeId=" + storeA, 200)).isEqualTo(valuation);

        // the reporting rebuild from empty gives the same reports
        List<JsonNode> live = reports(range);
        JsonNode rebuild = office.command(BO + "/reports/rebuild", Map.of("storeId", storeA, "from", day.toString(),
                "to", day.toString()), 202, "reporting rebuild");
        Awaitility.await().atMost(PROJECTED).pollInterval(Duration.ofMillis(250)).until(() ->
                office.get(BO + "/reports/status?storeId=" + storeA, 200).get("lastRebuild").get("status").asString(),
                s -> s.equals("COMPLETED") || s.equals("FAILED"));
        JsonNode status = office.get(BO + "/reports/status?storeId=" + storeA, 200).get("lastRebuild");
        assertThat(status.get("id").asString()).isEqualTo(id(rebuild).toString());
        assertThat(status.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(reports(range)).isEqualTo(live);
    }

    // ---- helpers ----

    /** The next 18:00 in the store's zone at least an hour from now: inside the happy hour, far from the cutoff. */
    private static Instant nextSixPmInTheStore() {
        ZonedDateTime now = ZonedDateTime.now(ZONE).plusHours(1);
        ZonedDateTime six = now.with(LocalTime.of(18, 0));
        return (six.isAfter(now) ? six : six.plusDays(1)).toInstant();
    }

    private UUID store(String suffix) throws Exception {
        return id(office.post(BO + "/stores", Map.of("code", "LOJA-" + suffix, "name", "Loja " + suffix,
                "timezone", Installation.ZONE, "businessDayCutoff", "05:00"), 201));
    }

    private Browser backOfficeUser(String username, UUID employeeId) throws Exception {
        String password = "pw " + UUID.randomUUID();
        office.post(BO + "/users", Map.of("username", username, "initialPassword", password, "employeeId",
                employeeId), 201);
        Browser tab = office.sibling(username);
        tab.get("/api/v1/auth/session", 401);
        tab.post("/api/v1/auth/login", Map.of("username", username, "password", password), 200);
        tab.post("/api/v1/auth/password", Map.of("currentPassword", password, "newPassword", password + " 2"), 200);
        return tab;
    }

    private UUID table(String code) throws Exception {
        return id(office.post(BO + "/tables", Map.of("storeId", storeA, "areaId", area, "code", code, "seats", 4),
                201));
    }

    private UUID printer(String code, TcpFakePrinter device, String codePage) throws Exception {
        return id(office.command(BO + "/printers", Map.of("storeId", storeA, "code", code, "name", "Impressora " + code,
                "host", device.host(), "port", device.port(), "codePage", codePage), 201, "printer " + code));
    }

    private UUID warehouse(UUID store, String code, String name) throws Exception {
        return id(office.command(INV + "/warehouses", Map.of("storeId", store, "code", code, "name", name), 201,
                "warehouse " + code));
    }

    private Map<String, Object> ingredient(String stockItem, String kind, List<String> modes) {
        Map<String, Object> ingredient = new LinkedHashMap<>();
        ingredient.put("stockItemId", stock.get(stockItem));
        ingredient.put("quantity", BigDecimal.ONE);
        ingredient.put("unit", "un");
        ingredient.put("kind", kind);
        ingredient.put("modes", modes);
        return ingredient;
    }

    private void recipe(String item, List<Map<String, Object>> ingredients) throws Exception {
        UUID recipe = id(office.command(INV + "/recipes", Map.of("itemId", items.get(item), "name", "Ficha " + item),
                201, "recipe " + item));
        office.command(INV + "/recipes/" + recipe + "/versions", Map.of("yieldQuantity", BigDecimal.ONE, "yieldUnit",
                "un", "ingredients", ingredients), 201, "recipe version " + item);
        office.command(INV + "/recipes/" + recipe + "/versions/1/activate", Map.of(), 200, "activate recipe " + item);
    }

    /** An opening count of {@code warehouse}: started (the store goes live), counted, submitted, approved. */
    private void opening(UUID warehouse, Map<String, String[]> lines) throws Exception {
        UUID count = id(office.command(INV + "/counts", Map.of("warehouseId", warehouse, "kind", "OPENING", "scope",
                "ALL"), 201, "opening count"));
        office.command(INV + "/counts/" + count + "/start", Map.of(), 200, "start the opening");
        Instant countedAt = clock.instant();
        for (Map.Entry<String, String[]> line : new TreeMap<>(lines).entrySet()) {
            office.put(INV + "/counts/" + count + "/lines/" + stock.get(line.getKey()), Map.of("countedQuantity",
                    new BigDecimal(line.getValue()[0]), "countedAt", countedAt.toString(), "unitCost",
                    new BigDecimal(line.getValue()[1])), 200, "opening " + line.getKey());
        }
        office.command(INV + "/counts/" + count + "/submit", Map.of(), 200, "submit the opening");
        manager.command(INV + "/counts/" + count + "/approve", Map.of(), 200, "approve the opening");
    }

    private UUID receipt(UUID supplier, UUID po, UUID poLine, String quantity, String cost, String reference)
            throws Exception {
        return id(office.command(INV + "/receipts", Map.of("warehouseId", kitchen, "supplierId", supplier,
                "purchaseOrderId", po, "supplierDocumentRef", reference, "lines", List.of(Map.of(
                        "purchaseOrderLineId", poLine, "stockItemId", stock.get("BIFE"), "packQuantity",
                        new BigDecimal(quantity), "unitCost", new BigDecimal(cost)))), 201, "receipt " + reference));
    }

    private UUID line(UUID order, String item, String quantity) throws Exception {
        return id(cashier.command(POS + "/orders/" + order + "/lines", Map.of("itemId", items.get(item),
                "quantity", new BigDecimal(quantity)), 201, "draft " + item));
    }

    private Map<String, Object> confirmation(UUID order, List<UUID> lines, String expectedGross) throws Exception {
        return Map.of("orderVersion", version(order), "catalogVersion", catalogVersion, "lineIds", lines,
                "promotionVersion", promotionVersion, "expectedGross", new BigDecimal(expectedGross));
    }

    private void pay(UUID order, UUID partition, String amount, String step) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", order);
        body.put("partitionId", partition);
        body.put("methodCode", "CASH");
        body.put("amount", new BigDecimal(amount));
        body.put("tendered", new BigDecimal(amount));
        cashier.command(POS + "/payment-attempts", body, 201, step);
    }

    private long version(UUID order) throws Exception {
        return cashier.get(POS + "/orders/" + order, 200).get("version").asLong();
    }

    private JsonNode settlement(UUID order) throws Exception {
        return cashier.get(POS + "/orders/" + order + "/settlement", 200);
    }

    private JsonNode openOrder(UUID order) throws Exception {
        for (JsonNode open : cashier.get(POS + "/settlement/open-orders", 200)) {
            if (open.get("orderId").asString().equals(order.toString())) {
                return open;
            }
        }
        throw new AssertionError("not an open order: " + order);
    }

    private JsonNode deliveryOf(UUID order) throws Exception {
        for (JsonNode delivery : cashier.get(POS + "/deliveries", 200)) {
            if (delivery.get("orderId").asString().equals(order.toString())) {
                return delivery;
            }
        }
        throw new AssertionError("no delivery for " + order);
    }

    private JsonNode tableOnMap(String code) throws Exception {
        for (JsonNode a : cashier.get(POS + "/tables", 200).get("areas")) {
            for (JsonNode t : a.get("tables")) {
                if (t.get("code").asString().equals(code)) {
                    return t;
                }
            }
        }
        throw new AssertionError("no table " + code);
    }

    private JsonNode reservationOf(UUID reservation) throws Exception {
        for (JsonNode r : cashier.get(POS + "/reservations?date=" + day, 200)) {
            if (r.get("id").asString().equals(reservation.toString())) {
                return r;
            }
        }
        throw new AssertionError("no reservation " + reservation);
    }

    private long points(UUID customer) throws Exception {
        return office.get(BO + "/loyalty/accounts/" + customer, 200).get("points").asLong();
    }

    private Map<String, String> approver() {
        Map<String, String> headers = new LinkedHashMap<>(Browser.idem());
        headers.put("X-Approver-Code", managerCode);
        headers.put("X-Approver-Pin", MANAGER_PIN);
        return headers;
    }

    private static JsonNode lineOf(JsonNode order, UUID line) {
        return linesWhere(order, l -> l.get("id").asString().equals(line.toString())).getFirst();
    }

    private static List<JsonNode> linesWhere(JsonNode order, Predicate<JsonNode> match) {
        return StreamSupport.stream(order.get("lines").spliterator(), false).filter(match).toList();
    }

    /** The round line (it has a batch) of {@code item} on the order. */
    private UUID batchLineOf(JsonNode order, String item) {
        return id(linesWhere(order, l -> l.get("itemId").asString().equals(items.get(item).toString())
                && !l.get("batchId").isNull()).getFirst());
    }

    private long saleMovementsOfBatch(UUID batch) {
        return count("""
                SELECT count(*) FROM inventory.stock_movement m JOIN orders.order_line l ON l.id = m.source_line_id
                WHERE l.batch_id = ? AND m.type = 'SALE_CONSUMPTION'""", batch);
    }

    private long saleMovementsOfOrder(UUID order) {
        return count("""
                SELECT count(*) FROM inventory.stock_movement m JOIN orders.order_line l ON l.id = m.source_line_id
                WHERE l.order_id = ? AND m.type = 'SALE_CONSUMPTION'""", order);
    }

    private long movementsOf(UUID warehouse, String item) {
        return count("SELECT count(*) FROM inventory.stock_movement WHERE warehouse_id = ? AND stock_item_id = ?",
                warehouse, stock.get(item));
    }

    private List<String> movementLines(UUID warehouse, String item) {
        return jdbc.sql("""
                        SELECT type || '|' || quantity || '|' || value FROM inventory.stock_movement
                        WHERE warehouse_id = ? AND stock_item_id = ? ORDER BY posting_seq""")
                .params(warehouse, stock.get(item)).query(String.class).list();
    }

    private String balanceRow(UUID warehouse, String item) {
        return jdbc.sql("SELECT quantity || '|' || value FROM inventory.stock_balance WHERE warehouse_id = ? "
                + "AND stock_item_id = ?").params(warehouse, stock.get(item)).query(String.class).optional()
                .orElse(null);
    }

    /** The balance row (quantity and value) and that Σ movements equals it (D113). */
    private void assertBalance(UUID warehouse, String item, String quantity, String value) {
        BigDecimal q = new BigDecimal(quantity).setScale(6, RoundingMode.UNNECESSARY);
        assertThat(balanceRow(warehouse, item)).as("balance of %s", item).isEqualTo(q.toPlainString() + "|" + value);
        assertThat(jdbc.sql("SELECT sum(quantity) || '|' || sum(value) FROM inventory.stock_movement "
                        + "WHERE warehouse_id = ? AND stock_item_id = ?").params(warehouse, stock.get(item))
                .query(String.class).single()).as("Σ movements of %s", item).isEqualTo(q.toPlainString() + "|" + value);
    }

    private BigDecimal averageCost(UUID warehouse, String item) {
        return jdbc.sql("SELECT average_cost FROM inventory.stock_balance WHERE warehouse_id = ? "
                + "AND stock_item_id = ?").params(warehouse, stock.get(item)).query(BigDecimal.class).single();
    }

    private List<String> balances() {
        return jdbc.sql("""
                        SELECT warehouse_id || '|' || stock_item_id || '|' || quantity || '|' || value || '|'
                               || average_cost FROM inventory.stock_balance ORDER BY 1""").query(String.class).list();
    }

    private BigDecimal documentTax(UUID document) {
        return decimal("SELECT sum(tax) FROM fiscal.fiscal_document_tax WHERE document_id = ?", document);
    }

    /** A cost or sales report once nothing is pending for it (the listeners have caught up). */
    private JsonNode awaitFresh(String path) throws Exception {
        Awaitility.await().atMost(PROJECTED).pollInterval(Duration.ofMillis(250))
                .until(() -> office.get(path, 200).get("pendingFacts").asLong() == 0);
        return office.get(path, 200);
    }

    /** The margins, discounts and sales reports of {@code range}, without their {@code asOf}. */
    private List<JsonNode> reports(String range) throws Exception {
        List<JsonNode> reports = new ArrayList<>();
        for (String path : List.of("/reports/margins" + range + "&groupBy=item", "/reports/margins" + range
                + "&groupBy=family", "/reports/discounts" + range + "&groupBy=origin", "/reports/discounts" + range
                + "&groupBy=item", "/reports/sales" + range + "&groupBy=item")) {
            JsonNode report = awaitFresh(BO + path);
            ((ObjectNode) report).remove("asOf");
            reports.add(report);
        }
        return reports;
    }

    /** {@code fields} of each row of {@code report} by its key: money as its amount, numbers as plain strings. */
    private static Map<String, String[]> rows(JsonNode report, String... fields) {
        Map<String, String[]> rows = new TreeMap<>();
        for (JsonNode row : report.get("rows")) {
            String[] values = new String[fields.length];
            for (int i = 0; i < fields.length; i++) {
                JsonNode value = row.get(fields[i]);
                values[i] = value == null || value.isNull() ? null
                        : value.isObject() ? value.get("amount").asString()
                        : value.decimalValue().stripTrailingZeros().toPlainString();
            }
            rows.put(row.get("key").isNull() ? "null" : row.get("key").asString(), values);
        }
        return rows;
    }

    private static TcpFakePrinter open() {
        try {
            return TcpFakePrinter.accepting();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String awaitPrinted(TcpFakePrinter device, Charset charset, String marker, Predicate<String> also)
            throws InterruptedException {
        Predicate<String> match = s -> s.contains(marker) && also.test(s);
        List<String> printed = device.awaitPayloads(charset, match, 1, PRINTED);
        assertThat(printed).as("printed on port %d: %s", device.port(), marker).hasSize(1);
        return printed.getFirst();
    }

    private Map<String, BigDecimal> perKey(String sql, Object... params) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        for (Object param : params) {
            statement = statement.param(param);
        }
        Map<String, BigDecimal> rows = new TreeMap<>();
        statement.query((rs, n) -> Map.entry(rs.getString("k"), rs.getBigDecimal("v"))).list()
                .forEach(e -> rows.put(e.getKey(), e.getValue()));
        return rows;
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
