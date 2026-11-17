package com.xavier.backend.e2e;

import org.testcontainers.postgresql.PostgreSQLContainer;
import static com.xavier.backend.e2e.Installation.CASHIER_ROLE;
import static com.xavier.backend.e2e.Installation.MANAGER_ROLE;
import static com.xavier.backend.e2e.Installation.id;
import static com.xavier.backend.e2e.Installation.posLogin;
import static com.xavier.backend.e2e.Installation.terminal;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.production.TcpFakePrinter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
 * Row-level security end to end (PRD RLS S70, rls-decisions A70–A76): one installation on its own PostgreSQL, the
 * backend pool as {@code restaurantpro_app} with {@code ENFORCE}, the real HTTP API, security chain, CSRF and sessions,
 * three stores A, B and C, and the people the PRD names: a POS cashier of A, a BackOffice manager holding A only, one
 * holding A and B, one holding B only, one holding C only, and the installation-wide administrator.
 * <ol>
 *   <li>Store A sells: a table, a confirmed round, a cash payment, a document, the order closed, the cash session
 *       closed and its closing approved, and a sales report.</li>
 *   <li>The listener chain of the round (OrderBatchConfirmed → production ticket, sale consumption; then
 *       StockMovementsPosted → reporting) completes on the listener threads under the event's store scope: every row
 *       is in store A, every publication completes, and no store-scope violation is alerted.</li>
 *   <li>Each actor sees exactly its stores: the audit trail of the three warehouses lists A for the A manager, A and B
 *       for the A+B manager, all three for the administrator; another store's resource by id answers 403, never 404
 *       (D42 through {@code shared.store_of}).</li>
 *   <li>A76: a store-A-only manager ships stock to store B's warehouse, a store-B-only manager sees and receives it,
 *       a store-C-only manager gets 403 on it; each balance is in its own store.</li>
 * </ol>
 * Prices are gross with 16 % VAT: 2 × Prego 150.00 = 300.00.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Installation.FreshDatabase.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RlsAcceptanceTest {

    @Autowired
    PostgreSQLContainer freshPostgres;

    /** The installation's owner connection, for what onboarding and Keycloak would have created. */
    private JdbcClient owner() {
        return Installation.owner(freshPostgres);
    }

    static final String INITIAL_PASSWORD = "initial " + UUID.randomUUID();
    static final String NEW_PASSWORD = "changed " + UUID.randomUUID();
    static final String CASHIER_PIN = "5172";
    static final String BO = "/api/v1/backoffice";
    static final String POS = "/api/v1/pos";
    static final String INV = BO + "/inventory";
    static final Charset CP860 = Charset.forName("IBM860");
    static final Duration ASYNC = Duration.ofSeconds(30);

    static final TcpFakePrinter KITCHEN = open();

    @DynamicPropertySource
    static void bootstrapSettings(DynamicPropertyRegistry registry) throws IOException {
        Installation.bootstrap(registry, "Restaurante RLS", INITIAL_PASSWORD);
    }

    @AfterAll
    static void unplugPrinters() throws IOException {
        KITCHEN.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private final List<String> journey = new ArrayList<>();
    private final Instant started = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    Browser office;
    UUID storeA;
    UUID storeB;
    UUID storeC;
    UUID kitchenA;
    UUID warehouseB;
    UUID warehouseC;
    UUID steak;
    Map<String, UUID> items;

    @Test
    void eachActorSeesExactlyItsStoresWhileStoreASellsAndShipsToB() throws Exception {
        try {
            run();
        } finally {
            System.out.println("RLS acceptance journey (step -> HTTP status):\n  " + String.join("\n  ", journey));
        }
    }

    private void run() throws Exception {
        // ---- setup by the installation-wide administrator ----
        office = new Browser("backoffice", port, journey);
        Installation.firstLogin(office, owner());
        storeA = Installation.store(office, "A");
        storeB = Installation.store(office, "B");
        storeC = Installation.store(office, "C");
        UUID terminalA = terminal(office, storeA, "POS1");
        String cashierCode = Installation.employee(office, storeA, "CX1", "Carla", CASHIER_PIN, CASHIER_ROLE);
        UUID area = id(office.post(BO + "/areas", Map.of("storeId", storeA, "code", "SALA", "name", "Sala"), 201));
        UUID table = id(office.post(BO + "/tables", Map.of("storeId", storeA, "areaId", area, "code", "M1",
                "seats", 4), 201));
        items = Installation.publishCatalog(office, storeA);
        UUID printer = id(office.command(BO + "/printers", Map.of("storeId", storeA, "code", "COZ", "name",
                "Cozinha", "host", KITCHEN.host(), "port", KITCHEN.port(), "codePage", "CP860"), 201, "printer"));
        office.put(BO + "/production-routes", Map.of("storeId", storeA, "routes", List.of(
                Map.of("destinationCode", "COZINHA", "printerId", printer),
                Map.of("destinationCode", "BAR", "printerId", printer))), 200, "routes");
        UUID register = id(office.command(BO + "/cash/registers", Map.of("storeId", storeA, "code", "CX1",
                "name", "Caixa 1"), 201, "register"));
        office.command(BO + "/fiscal/series", Map.of("storeId", storeA, "code", "FR", "firstNumber", 1), 201,
                "series");

        Browser managerA = manager("ana", "GA", storeA);
        Browser managerAB = manager("bruno", "GAB", storeA, storeB);
        Browser managerB = manager("beatriz", "GB", storeB);
        Browser managerC = manager("celia", "GC", storeC);

        inventorySetUp(managerA);

        // ---- 1. store A sells ----
        Browser cashier = posLogin(office, terminalA, cashierCode, CASHIER_PIN, "cashier");
        UUID session = id(cashier.command(POS + "/cash/sessions", Map.of("registerId", register, "openingFloat",
                new BigDecimal("100.00")), 201, "open session"));
        long catalogVersion = cashier.get(POS + "/catalog?storeId=" + storeA, 200).get("version").asLong();
        UUID order = id(cashier.command(POS + "/tables/" + table + "/open", Map.of("guests", 2), 201, "open M1"));
        UUID prego = id(cashier.command(POS + "/orders/" + order + "/lines", Map.of("itemId", items.get("PREGO"),
                "quantity", new BigDecimal("2")), 201, "draft 2 pregos"));
        UUID batch = id(cashier.command(POS + "/orders/" + order + "/batches", Map.of("orderVersion",
                version(cashier, order), "catalogVersion", catalogVersion, "lineIds", List.of(prego)), 201,
                "confirm round 1"));

        cashier.command(POS + "/payment-attempts", Map.of("orderId", order, "methodCode", "CASH", "amount",
                new BigDecimal("300.00"), "tendered", new BigDecimal("300.00")), 201, "pay 300.00 in cash");
        JsonNode document = cashier.command(POS + "/orders/" + order + "/documents", Map.of(), 201, "document");
        assertThat(document.get("status").asString()).isEqualTo("ISSUED");
        cashier.command(POS + "/orders/" + order + "/close", Map.of("orderVersion", cashier.get(POS + "/orders/"
                + order + "/settlement", 200).get("orderVersion").asLong()), 200, "close order");

        cashier.command(POS + "/cash/sessions/" + session + "/close", Map.of("version", cashier.get(POS
                + "/cash/sessions/" + session, 200).get("version").asLong(), "counts", List.of(Map.of("methodCode",
                "CASH", "amount", new BigDecimal("400.00")))), 200, "close session (100.00 float + 300.00)");
        JsonNode closing = cashier.command(POS + "/cash/closings", Map.of("registerId", register, "cashDayId",
                cashier.get(POS + "/cash/sessions/" + session, 200).get("cashDayId").asString()), 201,
                "create closing");
        closing.get("lines").forEach(line -> assertThat(new BigDecimal(line.get("difference").get("amount")
                .asString())).isEqualByComparingTo("0"));
        cashier.command(POS + "/cash/closings/" + id(closing) + "/submit", null, 200, "submit closing");
        assertThat(managerA.command(BO + "/cash/closings/" + id(closing) + "/approve", null, 200,
                "the A manager approves the closing").get("status").asString()).isEqualTo("APPROVED");

        // ---- 2. the round's listener chain ran in store A on the listener threads ----
        assertThat(KITCHEN.awaitPayloads(CP860, s -> s.contains("2 x Prego no pao"), 1, ASYNC)).hasSize(1);
        Awaitility.await().atMost(ASYNC).until(() -> count("""
                SELECT count(*) FROM reporting.consumption_record WHERE store_id = ? AND warehouse_id = ?""",
                storeA, kitchenA) == 1);
        assertThat(count("SELECT count(*) FROM production.production_ticket WHERE source_id = ? AND store_id = ?",
                batch, storeA)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM inventory.stock_movement WHERE type = 'SALE_CONSUMPTION' AND warehouse_id = ?
                  AND store_id = ? AND quantity = -2""", kitchenA, storeA)).isEqualTo(1);
        Awaitility.await().atMost(ASYNC).until(() -> count("""
                SELECT count(*) FROM event_publication WHERE completion_date IS NULL""") == 0);
        assertThat(alertCodes(office)).as("no store-scope violation anywhere").doesNotContain("STORE_SCOPE_VIOLATION");

        // the report of A, for its manager; B's is refused
        String days = "&from=" + LocalDate.now(ZoneId.of(Installation.ZONE)).minusDays(1) + "&to="
                + LocalDate.now(ZoneId.of(Installation.ZONE)).plusDays(1);
        Awaitility.await().atMost(ASYNC).until(() -> managerA.get(BO + "/reports/sales?storeId=" + storeA + days
                + "&groupBy=item", 200).get("pendingFacts").asLong() == 0);
        JsonNode sales = managerA.get(BO + "/reports/sales?storeId=" + storeA + days + "&groupBy=item", 200);
        assertThat(sales.get("rows")).singleElement().satisfies(row -> {
            assertThat(row.get("key").asString()).isEqualTo("PREGO");
            assertThat(new BigDecimal(row.get("gross").get("amount").asString())).isEqualByComparingTo("300.00");
        });
        managerA.get(BO + "/reports/sales?storeId=" + storeB + days + "&groupBy=item", 403);
        managerAB.get(BO + "/reports/sales?storeId=" + storeB + days + "&groupBy=item", 200);

        // ---- 3. each actor sees exactly its stores ----
        assertThat(warehouseAuditStores(managerA)).containsExactly(storeA);
        assertThat(warehouseAuditStores(managerAB)).containsExactlyInAnyOrder(storeA, storeB);
        assertThat(warehouseAuditStores(managerB)).containsExactly(storeB);
        assertThat(warehouseAuditStores(office)).containsExactlyInAnyOrder(storeA, storeB, storeC);

        UUID listA = priceListOf(storeA);
        UUID listB = id(office.post(Installation.CATALOG + "/price-lists", Map.of("storeId", storeB, "name",
                "Sala B"), 201));
        managerA.get(Installation.CATALOG + "/price-lists/" + listA, 200);
        managerA.get(Installation.CATALOG + "/price-lists/" + listB, 403);
        managerA.get(Installation.CATALOG + "/price-lists/" + UUID.randomUUID(), 404);
        managerAB.get(Installation.CATALOG + "/price-lists/" + listB, 200);
        managerC.get(Installation.CATALOG + "/price-lists/" + listA, 403);
        office.get(Installation.CATALOG + "/price-lists/" + listB, 200);
        cashier.get(POS + "/tables?storeId=" + storeA, 200);
        cashier.get(POS + "/tables?storeId=" + storeB, 403);
        cashier.get(POS + "/orders/" + order, 200);
        managerB.get(INV + "/reports/valuation?storeId=" + storeA, 403);
        managerA.get(INV + "/reports/valuation?storeId=" + storeA, 200);

        // ---- 4. A76: A ships to B, B receives, C is refused ----
        UUID transfer = id(managerA.command(INV + "/transfers", Map.of("fromWarehouseId", kitchenA, "toWarehouseId",
                warehouseB, "lines", List.of(Map.of("stockItemId", steak, "quantity", new BigDecimal("3")))), 201,
                "the A manager transfers 3 steaks to B"));
        managerA.command(INV + "/transfers/" + transfer + "/ship", Map.of(), 200, "ship");
        assertThat(managerB.get(INV + "/transfers/" + transfer, 200).get("status").asString()).isEqualTo("SHIPPED");
        managerC.get(INV + "/transfers/" + transfer, 403);
        managerC.command(INV + "/transfers/" + transfer + "/receive", Map.of("lines", List.of(Map.of("stockItemId",
                steak, "quantityReceived", new BigDecimal("3")))), 403, "a store-C manager receives");
        assertThat(managerB.command(INV + "/transfers/" + transfer + "/receive", Map.of("lines", List.of(Map.of(
                "stockItemId", steak, "quantityReceived", new BigDecimal("3")))), 200, "the B manager receives")
                .get("status").asString()).isEqualTo("RECEIVED");
        // A: 20 opened at 10.00, 2 sold, 3 shipped → 15, 150.00; B: 3 in at the OUT's 30.00
        assertThat(balance(kitchenA)).isEqualTo(storeA + "|15|150.0000");
        assertThat(balance(warehouseB)).isEqualTo(storeB + "|3|30.0000");
        assertThat(count("SELECT count(*) FROM inventory.stock_movement WHERE warehouse_id = ? AND store_id <> ?",
                warehouseB, storeB)).isZero();
    }

    /**
     * Kitchen warehouse of A with the default route, one of B and one of C; a steak, a prego's recipe of one steak;
     * A live by an opening count of 20 steaks at 10.00 (approved by the A manager), B by activation.
     */
    private void inventorySetUp(Browser managerA) throws Exception {
        kitchenA = id(office.command(INV + "/warehouses", Map.of("storeId", storeA, "code", "COZ", "name",
                "Cozinha"), 201, "warehouse A"));
        warehouseB = id(office.command(INV + "/warehouses", Map.of("storeId", storeB, "code", "ARM", "name",
                "Armazem B"), 201, "warehouse B"));
        warehouseC = id(office.command(INV + "/warehouses", Map.of("storeId", storeC, "code", "ARM", "name",
                "Armazem C"), 201, "warehouse C"));
        office.command(INV + "/consumption-routes", Map.of("storeId", storeA, "warehouseId", kitchenA), 201,
                "default route");
        steak = id(office.command(INV + "/stock-items", Map.of("code", "BIFE", "name", "Bife", "baseUnit", "un",
                "kind", "PURCHASED"), 201, "stock item"));
        UUID recipe = id(office.command(INV + "/recipes", Map.of("itemId", items.get("PREGO"), "name",
                "Ficha prego"), 201, "recipe"));
        office.command(INV + "/recipes/" + recipe + "/versions", Map.of("yieldQuantity", BigDecimal.ONE,
                "yieldUnit", "un", "ingredients", List.of(Map.of("stockItemId", steak, "quantity", BigDecimal.ONE,
                        "unit", "un", "kind", "INGREDIENT"))), 201, "recipe version");
        office.command(INV + "/recipes/" + recipe + "/versions/1/activate", Map.of(), 200, "activate recipe");
        UUID count = id(office.command(INV + "/counts", Map.of("warehouseId", kitchenA, "kind", "OPENING", "scope",
                "ALL"), 201, "opening count"));
        office.command(INV + "/counts/" + count + "/start", Map.of(), 200, "start the opening");
        office.put(INV + "/counts/" + count + "/lines/" + steak, Map.of("countedQuantity", new BigDecimal("20"),
                "countedAt", Instant.now().toString(), "unitCost", new BigDecimal("10.00")), 200, "count 20 steaks");
        office.command(INV + "/counts/" + count + "/submit", Map.of(), 200, "submit the opening");
        managerA.command(INV + "/counts/" + count + "/approve", Map.of(), 200, "the A manager approves");
        office.command(INV + "/activations", Map.of("storeId", storeB), 201, "store B goes live");
        assertThat(balance(kitchenA)).isEqualTo(storeA + "|20|200.0000");
    }

    /** A BackOffice account of an employee of {@code stores[0]}, with the manager role in each of {@code stores}. */
    private Browser manager(String username, String code, UUID... stores) throws Exception {
        UUID employee = id(office.post(BO + "/employees", Map.of("code", code, "name", username, "pin", "9182",
                "storeId", stores[0], "roleId", MANAGER_ROLE), 201));
        for (int i = 1; i < stores.length; i++) {
            office.post(BO + "/employees/" + employee + "/assignments", Map.of("storeId", stores[i], "roleId",
                    MANAGER_ROLE), 201);
        }
        Browser tab = Installation.user(office, owner(), username, employee, username);
        return tab;
    }

    /** The stores of the WAREHOUSE_CREATED audit rows {@code reader} may list (D82, filtered by its stores). */
    private Set<UUID> warehouseAuditStores(Browser reader) throws Exception {
        JsonNode page = reader.get(BO + "/audit/events?action=WAREHOUSE_CREATED&from=" + started + "&to="
                + Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS), 200);
        Set<UUID> stores = new TreeSet<>();
        page.get("content").forEach(row -> stores.add(UUID.fromString(row.get("storeId").asString())));
        return stores;
    }

    private UUID priceListOf(UUID store) throws Exception {
        return id(office.get(Installation.CATALOG + "/price-lists?storeId=" + store, 200).get(0));
    }

    private static List<String> alertCodes(Browser reader) throws Exception {
        List<String> codes = new ArrayList<>();
        reader.get(BO + "/alerts", 200).get("alerts").forEach(alert -> codes.add(alert.get("code").asString()));
        return codes;
    }

    private static long version(Browser pos, UUID order) throws Exception {
        return pos.get(POS + "/orders/" + order, 200).get("version").asLong();
    }

    private String balance(UUID warehouse) {
        return jdbc.sql("""
                        SELECT store_id || '|' || trim_scale(quantity) || '|' || value FROM inventory.stock_balance
                        WHERE warehouse_id = ? AND stock_item_id = ?""")
                .param(warehouse).param(steak).query(String.class).single();
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
}
