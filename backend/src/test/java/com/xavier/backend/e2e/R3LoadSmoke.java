package com.xavier.backend.e2e;

import static com.xavier.backend.e2e.Installation.CASHIER_ROLE;
import static com.xavier.backend.e2e.Installation.MANAGER_ROLE;
import static com.xavier.backend.e2e.Installation.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.production.TcpFakePrinter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * The 10-terminal load smoke of spec §12 (PRD S45, D136, A56; informational, like {@link R2LatencySmoke}). One store
 * is seeded through the BackOffice API (10 enrolled terminals, 10 cashiers and a manager, 40 tables, a published
 * catalog of 300 items at 16 % and 23 % VAT, 10 registers, a fiscal series, 10 customers with a credit limit, kitchen,
 * bar and counter printers on {@link TcpFakePrinter}s). Then 10 POS tabs, each with its own PIN session, cash
 * session and {@link ChangeStream}, run the R3 table cycle concurrently for a fixed time through the real HTTP API
 * and the real security chain (sessions in PostgreSQL, CSRF, Idempotency-Key on every command):
 * <ol>
 *   <li>map and catalog reads; open one of the terminal's 4 tables;</li>
 *   <li>two rounds (draft lines, read the order, confirm the batch); read the order; print the subtotal; read the
 *       settlement;</li>
 *   <li>one of four settlements in turn: equal parts paid in cash; an ITEMS partition paid in cash with its own
 *       document, the rest in cash; a credit sale of the whole bill; a cash payment, a refund of 1.00 with the
 *       manager as approver, and the 1.00 paid again;</li>
 *   <li>close the order (the residual document).</li>
 * </ol>
 * A BackOffice tab requests one 1-day report (sales by item and by hour, taxes, receipts by method) per closed order,
 * as a manager watching the day. Every SSE stream must see the other terminals' work: the fan-out delay of each
 * close's {@code ORDER} notice to the 9 other streams is reported too (notices may be dropped by design, D60, so the
 * delivery count is reported, not asserted).
 *
 * <p>The p50/p95/p99 of each operation are printed against the §12 target (p95 &lt; 500 ms for simple local commands
 * and reads; physical printing and external services excluded: printing is asynchronous and no integrated provider is
 * used), with the throughput, the hardware and the data volume, and written as JSON to
 * {@code target/load-smoke/load-<UTC>.json}. The random choices use fixed seeds, so runs are comparable. A miss is
 * reported as MISS; the test fails on any failed request (the error count must be 0) and, only with
 * {@code -Dload.gate=true}, on a MISS.
 *
 * <p><b>Not part of the normal suite, and never skipped:</b> the class name matches none of Surefire's default
 * include patterns, so {@code mvn test} never discovers it. Run it by name:
 * <pre>rtk mvn test -Dtest=R3LoadSmoke [-Dload.seconds=60] [-Dload.terminals=10] [-Dload.gate=true]</pre>
 * It runs against its own empty PostgreSQL container ({@link Installation.FreshDatabase}), closed with its context,
 * so the database shared by the rest of the suite is never touched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Installation.FreshDatabase.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R3LoadSmoke {

    static final String INITIAL_PASSWORD = "initial " + UUID.randomUUID();
    static final String NEW_PASSWORD = "changed " + UUID.randomUUID();
    static final String PIN = "7391";
    static final String MANAGER_PIN = "6283";
    static final String POS = "/api/v1/pos";
    static final String BO = "/api/v1/backoffice";
    static final String CATALOG = BO + "/catalog";
    static final int TERMINALS = Integer.getInteger("load.terminals", 10);
    static final Duration RUN = Duration.ofSeconds(Long.getLong("load.seconds", 60));
    static final boolean GATE = Boolean.getBoolean("load.gate");
    static final int ITEMS = 300;
    static final int TABLES = 40;
    static final long TARGET_MS = 500;
    static final long SEED = 45;
    static final String[] REPORTS = {"sales?groupBy=item", "taxes?", "receipts?groupBy=method", "sales?groupBy=hour"};

    static final TcpFakePrinter KITCHEN = open();
    static final TcpFakePrinter BAR = open();
    static final TcpFakePrinter COUNTER = open();

    @DynamicPropertySource
    static void bootstrapSettings(DynamicPropertyRegistry registry) throws IOException {
        Installation.bootstrap(registry, "Restaurante Load", INITIAL_PASSWORD);
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

    /** One POS terminal: its tab, its tables, its customer and its seeded choices. */
    record Terminal(int no, Browser tab, List<UUID> tables, UUID customer, Random random) {
    }

    /** A request that answered outside 2xx: counted, and it ends that terminal's loop. */
    static final class RequestFailed extends Exception {
        RequestFailed(String message) {
            super(message);
        }
    }

    // the installation
    Browser office;
    UUID store;
    UUID area;
    List<UUID> items = new ArrayList<>();
    long catalogVersion;
    String managerCode;
    String businessDate;

    // the measurements
    final Map<String, List<Long>> latencies = new ConcurrentHashMap<>();
    final List<String> errors = new CopyOnWriteArrayList<>();
    final AtomicInteger requests = new AtomicInteger();
    final AtomicInteger ordersClosed = new AtomicInteger();
    final Map<String, AtomicInteger> settlements = new ConcurrentHashMap<>();
    /** orderId → (closing terminal, nanoTime when the close request started). */
    final Map<UUID, long[]> closes = new ConcurrentHashMap<>();

    @Test
    void tenTerminalsRunTheR3TableCycle() throws Exception {
        List<Terminal> terminals = seed();
        List<ChangeStream> streams = new ArrayList<>();
        try {
            for (Terminal terminal : terminals) {
                ChangeStream stream = ChangeStream.subscribe(terminal.tab());
                streams.add(stream);
                stream.makeLive(attempt -> id(office.post(BO + "/tables", Map.of("storeId", store, "areaId", area,
                        "code", "S" + terminal.no() + "-" + attempt, "seats", 2), 201)), "terminal " + terminal.no());
            }

            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean running = new AtomicBoolean(true);
            BlockingQueue<UUID> closedOrders = new LinkedBlockingQueue<>();
            List<Future<Integer>> runs = new ArrayList<>();
            long began;
            long ended;
            try (ExecutorService pool = Executors.newFixedThreadPool(TERMINALS + 1)) {
                for (Terminal terminal : terminals) {
                    runs.add(pool.submit(cycle(terminal, start, closedOrders)));
                }
                Future<Integer> reporter = pool.submit(reporter(start, running, closedOrders));
                began = System.nanoTime();
                start.countDown();
                int cycles = 0;
                for (Future<Integer> run : runs) {
                    cycles += run.get();
                }
                running.set(false);
                reporter.get();
                ended = System.nanoTime();
                assertThat(cycles).isEqualTo(ordersClosed.get());
            }
            // the last closes' notices are on their way: wait for the sender to drain (bounded), then measure
            awaitQuietStreams(streams);
            String report = report(Duration.ofNanos(ended - began), fanOut(terminals, streams));
            System.out.println(report);
        } finally {
            streams.forEach(ChangeStream::close);
        }
        assertThat(errors).as("failed requests (the error count must be 0)").isEmpty();
        if (GATE) {
            assertThat(misses()).as("operations whose p95 is not below %d ms (§12)", TARGET_MS).isEmpty();
        }
    }

    // ---- the seed ----

    private List<Terminal> seed() throws Exception {
        List<String> setup = new ArrayList<>();
        office = new Browser("backoffice", port, setup);
        Installation.firstLogin(office, INITIAL_PASSWORD, NEW_PASSWORD);
        store = Installation.store(office, "LOAD");
        area = id(office.post(BO + "/areas", Map.of("storeId", store, "code", "SALA", "name", "Sala"), 201));
        publishCatalog(new Random(SEED));
        UUID kitchen = printer("COZ", KITCHEN);
        UUID bar = printer("BAR", BAR);
        UUID counter = printer("BALCAO", COUNTER);
        office.put(BO + "/production-routes", Map.of("storeId", store, "routes", List.of(
                Map.of("destinationCode", "COZINHA", "printerId", kitchen),
                Map.of("destinationCode", "BAR", "printerId", bar))), 200, "routes");
        office.command(BO + "/fiscal/series", Map.of("storeId", store, "code", "FR", "firstNumber", 1), 201,
                "series");
        managerCode = Installation.employee(office, store, "GR1", "Gerente", MANAGER_PIN, MANAGER_ROLE);
        List<UUID> tables = new ArrayList<>();
        for (int t = 1; t <= TABLES; t++) {
            tables.add(id(office.post(BO + "/tables", Map.of("storeId", store, "areaId", area, "code", "M" + t,
                    "seats", 4), 201)));
        }
        List<UUID> terminalIds = new ArrayList<>();
        List<Map<String, Object>> assignments = new ArrayList<>();
        for (int i = 1; i <= TERMINALS; i++) {
            UUID terminal = Installation.terminal(office, store, "POS" + i);
            terminalIds.add(terminal);
            assignments.add(Map.of("terminalId", terminal, "printerId", counter));
        }
        office.put(BO + "/terminal-printers", Map.of("storeId", store, "assignments", assignments), 200,
                "terminal printers");
        int perTerminal = TABLES / TERMINALS;
        List<Terminal> terminals = new ArrayList<>();
        for (int i = 1; i <= TERMINALS; i++) {
            String code = Installation.employee(office, store, "E" + i, "Empregado " + i, PIN, CASHIER_ROLE);
            UUID register = id(office.command(BO + "/cash/registers", Map.of("storeId", store, "code", "CX" + i,
                    "name", "Caixa " + i), 201, "register " + i));
            UUID customer = id(office.command(BO + "/customers", Map.of("name", "Cliente " + i,
                    "phone", "8412345" + String.format("%02d", i), "taxId", "1002003" + String.format("%02d", i)),
                    201, "customer " + i));
            office.command(BO + "/customers/" + customer + "/credit-limit-changes", Map.of("newLimit",
                    new BigDecimal("1000000.00"), "reason", "Carga"), 200, "credit limit " + i);
            Browser tab = Installation.posLogin(office, terminalIds.get(i - 1), code, PIN, "pos" + i);
            JsonNode session = tab.command(POS + "/cash/sessions", Map.of("registerId", register,
                    "openingFloat", new BigDecimal("1000.00")), 201, "open session " + i);
            businessDate = session.get("businessDate").asString();
            List<UUID> own = tables.subList((i - 1) * perTerminal, i * perTerminal);
            terminals.add(new Terminal(i, tab, own, customer, new Random(SEED * 1000 + i)));
        }
        catalogVersion = terminals.getFirst().tab().get(POS + "/catalog?storeId=" + store, 200).get("version")
                .asLong();
        return terminals;
    }

    /** {@link #ITEMS} items with seeded prices (10.00–399.99), two thirds at 16 % and one third at 23 % VAT. */
    private void publishCatalog(Random random) throws Exception {
        UUID family = id(office.post(CATALOG + "/families", Map.of("code", "REST", "name", "Restaurante"), 201));
        UUID unit = id(office.post(CATALOG + "/units", Map.of("code", "UN", "name", "Unidade"), 201));
        Map<String, UUID> taxes = new LinkedHashMap<>();
        for (String rate : List.of("16", "23")) {
            UUID tax = id(office.post(CATALOG + "/tax-categories", Map.of("code", "IVA" + rate, "name",
                    "IVA " + rate), 201));
            office.post(CATALOG + "/tax-categories/" + tax + "/rates", Map.of("ratePercent", new BigDecimal(rate),
                    "effectiveFrom", "2000-01-01T00:00:00Z"), 201);
            taxes.put(rate, tax);
        }
        UUID priceList = id(office.post(CATALOG + "/price-lists", Map.of("storeId", store, "name", "Sala"), 201));
        for (int i = 1; i <= ITEMS; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", "I" + i);
            item.put("name", "Prato " + i);
            item.put("familyId", family);
            item.put("unitId", unit);
            item.put("taxCategoryId", taxes.get(i % 3 == 0 ? "23" : "16"));
            item.put("productionDestination", i % 2 == 0 ? "BAR" : "COZINHA");
            item.put("soldByWeight", false);
            UUID itemId = id(office.post(CATALOG + "/items", item, 201));
            office.post(CATALOG + "/price-lists/" + priceList + "/prices", Map.of("itemId", itemId,
                    "amount", BigDecimal.valueOf(1000 + random.nextInt(39_000), 2)), 201);
            items.add(itemId);
        }
        UUID release = id(office.post(CATALOG + "/releases", Map.of("storeId", store), 201));
        office.post(CATALOG + "/releases/" + release + "/validate", null, 200);
        office.command(CATALOG + "/releases/" + release + "/publish", Map.of("note", "Carga"), 200, "publish");
    }

    // ---- the terminal loop ----

    private Callable<Integer> cycle(Terminal terminal, CountDownLatch start, BlockingQueue<UUID> closedOrders) {
        return () -> {
            start.await();
            long until = System.nanoTime() + RUN.toNanos();
            int cycles = 0;
            try {
                while (System.nanoTime() < until) {
                    UUID order = tableCycle(terminal, cycles);
                    cycles++;
                    ordersClosed.incrementAndGet();
                    closedOrders.add(order);
                }
            } catch (RequestFailed failed) {
                errors.add("terminal " + terminal.no() + ": " + failed.getMessage());
            }
            return cycles;
        };
    }

    private UUID tableCycle(Terminal terminal, int cycle) throws Exception {
        Browser tab = terminal.tab();
        Random random = terminal.random();
        timed(tab, "GET /pos/tables (map)", "GET", POS + "/tables?storeId=" + store, null, Map.of());
        timed(tab, "GET /pos/catalog", "GET", POS + "/catalog?storeId=" + store, null, Map.of());
        UUID table = terminal.tables().get(cycle % terminal.tables().size());
        UUID order = id(timed(tab, "POST /pos/tables/{id}/open", "POST", POS + "/tables/" + table + "/open",
                Map.of("guests", 1 + random.nextInt(4)), Browser.idem()));
        List<UUID> roundOne = round(tab, random, order, 2 + random.nextInt(3));
        round(tab, random, order, 1 + random.nextInt(2));
        timed(tab, "GET /pos/orders/{id}", "GET", POS + "/orders/" + order, null, Map.of());
        timed(tab, "POST /pos/orders/{id}/subtotal/print", "POST", POS + "/orders/" + order + "/subtotal/print",
                null, Browser.idem());
        JsonNode settlement = settlement(tab, order);
        switch (cycle % 4) {
            case 0 -> equalParts(tab, random, order, settlement);
            case 1 -> itemsPartition(tab, order, roundOne.getFirst(), settlement);
            case 2 -> creditSale(terminal, order, settlement);
            default -> cashWithRefund(tab, order, settlement);
        }
        long closing = System.nanoTime();
        closes.put(order, new long[] {terminal.no(), closing});
        JsonNode closed = timed(tab, "POST /pos/orders/{id}/close", "POST", POS + "/orders/" + order + "/close",
                Map.of("orderVersion", settlement(tab, order).get("orderVersion").asLong()), Browser.idem());
        if (!"CLOSED".equals(closed.get("lifecycle").asString())) {
            throw new RequestFailed("close of " + order + " answered " + closed);
        }
        return order;
    }

    /** Drafts {@code lines} seeded lines, reads the order for its version and confirms them as one batch. */
    private List<UUID> round(Browser tab, Random random, UUID order, int lines) throws Exception {
        List<UUID> drafted = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            drafted.add(id(timed(tab, "POST /pos/orders/{id}/lines (draft)", "POST", POS + "/orders/" + order
                    + "/lines", Map.of("itemId", items.get(random.nextInt(items.size())),
                    "quantity", BigDecimal.valueOf(1 + random.nextInt(3))), Browser.idem())));
        }
        long version = timed(tab, "GET /pos/orders/{id}", "GET", POS + "/orders/" + order, null, Map.of())
                .get("version").asLong();
        timed(tab, "POST /pos/orders/{id}/batches (confirm)", "POST", POS + "/orders/" + order + "/batches",
                Map.of("orderVersion", version, "catalogVersion", catalogVersion, "lineIds", drafted),
                Browser.idem());
        return drafted;
    }

    private JsonNode settlement(Browser tab, UUID order) throws Exception {
        return timed(tab, "GET /pos/orders/{id}/settlement", "GET", POS + "/orders/" + order + "/settlement", null,
                Map.of());
    }

    /** The whole bill in 2 or 3 equal parts, each paid in cash (D69: the cents go to the first parts). */
    private void equalParts(Browser tab, Random random, UUID order, JsonNode settlement) throws Exception {
        settled("equal parts in cash");
        JsonNode parts = timed(tab, "POST /pos/orders/{id}/partitions/equal", "POST", POS + "/orders/" + order
                + "/partitions/equal", Map.of("orderVersion", settlement.get("orderVersion").asLong(),
                "parts", 2 + random.nextInt(2)), Browser.idem());
        for (JsonNode part : parts) {
            cash(tab, order, id(part), money(part.get("due")));
        }
    }

    /** The first line in a partition of its own, paid in cash, with its own document; the rest in cash. */
    private void itemsPartition(Browser tab, UUID order, UUID line, JsonNode settlement) throws Exception {
        settled("items partition + document, rest in cash");
        JsonNode lines = timed(tab, "GET /pos/orders/{id}", "GET", POS + "/orders/" + order, null, Map.of())
                .get("lines");
        BigDecimal quantity = new BigDecimal(StreamSupport.stream(lines.spliterator(), false)
                .filter(l -> l.get("id").asString().equals(line.toString())).findFirst().orElseThrow()
                .get("quantity").asString());
        JsonNode partition = timed(tab, "POST /pos/orders/{id}/partitions (items)", "POST", POS + "/orders/" + order
                + "/partitions", Map.of("orderVersion", settlement.get("orderVersion").asLong(), "kind", "ITEMS",
                "label", "Cliente A", "lines", List.of(Map.of("lineId", line, "quantity", quantity))),
                Browser.idem());
        cash(tab, order, id(partition), money(partition.get("due")));
        timed(tab, "POST /pos/partitions/{id}/documents", "POST", POS + "/partitions/" + id(partition)
                + "/documents", Map.of(), Browser.idem());
        cash(tab, order, null, money(settlement(tab, order).get("residualBalance")));
    }

    /** The whole bill on the terminal's customer's account: the document is issued at once (D97). */
    private void creditSale(Terminal terminal, UUID order, JsonNode settlement) throws Exception {
        settled("credit sale");
        JsonNode charge = timed(terminal.tab(), "POST /pos/orders/{id}/credit-charges", "POST", POS + "/orders/"
                + order + "/credit-charges", Map.of("customerId", terminal.customer(),
                "amount", money(settlement.get("due"))), Browser.idem());
        if (!"POSTED".equals(charge.get("status").asString())) {
            throw new RequestFailed("credit charge of " + order + " answered " + charge);
        }
    }

    /** The bill in cash, a refund of 1.00 approved by the manager (before any document), and the 1.00 again. */
    private void cashWithRefund(Browser tab, UUID order, JsonNode settlement) throws Exception {
        settled("cash, refund 1.00, cash 1.00");
        UUID attempt = cash(tab, order, null, money(settlement.get("due")));
        UUID payment = jdbc.sql("SELECT id FROM settlement.payment WHERE attempt_id = ?").param(attempt)
                .query(UUID.class).single();
        Map<String, String> approver = new LinkedHashMap<>(Browser.idem());
        approver.put("X-Approver-Code", managerCode);
        approver.put("X-Approver-Pin", MANAGER_PIN);
        timed(tab, "POST /pos/payments/{id}/refunds (approver)", "POST", POS + "/payments/" + payment + "/refunds",
                Map.of("amount", new BigDecimal("1.00"), "reason", "Carga"), approver);
        cash(tab, order, null, new BigDecimal("1.00"));
    }

    private UUID cash(Browser tab, UUID order, UUID partition, BigDecimal amount) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", order);
        body.put("partitionId", partition);
        body.put("methodCode", "CASH");
        body.put("amount", amount);
        body.put("tendered", amount);
        return id(timed(tab, "POST /pos/payment-attempts (cash)", "POST", POS + "/payment-attempts", body,
                Browser.idem()));
    }

    private void settled(String kind) {
        settlements.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
    }

    // ---- the BackOffice reporter ----

    /** One 1-day report per closed order, in turn, until the terminals stop and every closed order is reported. */
    private Callable<Integer> reporter(CountDownLatch start, AtomicBoolean running, BlockingQueue<UUID> closed) {
        return () -> {
            start.await();
            int done = 0;
            try {
                while (running.get() || !closed.isEmpty()) {
                    if (closed.poll(200, TimeUnit.MILLISECONDS) == null) {
                        continue;
                    }
                    String report = REPORTS[done % REPORTS.length];
                    String path = BO + "/reports/" + report + (report.endsWith("?") ? "" : "&") + "storeId=" + store
                            + "&from=" + businessDate + "&to=" + businessDate;
                    timed(office, "GET /backoffice/reports/" + report.replace("?", " ").strip() + " (1 d)", "GET",
                            path, null, Map.of());
                    done++;
                }
            } catch (RequestFailed failed) {
                errors.add("reporter: " + failed.getMessage());
            }
            return done;
        };
    }

    // ---- measuring ----

    private JsonNode timed(Browser tab, String operation, String method, String path, Object body,
                           Map<String, String> headers) throws Exception {
        long begin = System.nanoTime();
        HttpResponse<String> response = tab.send(method, path, body, Browser.Csrf.SEND, headers);
        long elapsed = System.nanoTime() - begin;
        requests.incrementAndGet();
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new RequestFailed(method + " " + path + " answered " + response.statusCode() + " "
                    + response.body());
        }
        latencies.computeIfAbsent(operation, k -> new CopyOnWriteArrayList<>()).add(elapsed);
        return response.body().isBlank() ? Browser.JSON.nullNode() : Browser.JSON.readTree(response.body());
    }

    /** Waits (at most 10 s) until no stream received anything for a while: the sender queue has drained. */
    private static void awaitQuietStreams(List<ChangeStream> streams) {
        int[] last = {-1};
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(500)).until(() -> {
            int now = streams.stream().mapToInt(s -> s.received().size()).sum();
            boolean quiet = now == last[0];
            last[0] = now;
            return quiet;
        });
    }

    /** For every close, the delay from its request start to the first ORDER notice of it on each other stream. */
    private FanOut fanOut(List<Terminal> terminals, List<ChangeStream> streams) {
        List<Long> delays = new ArrayList<>();
        int expected = 0;
        for (Map.Entry<UUID, long[]> close : closes.entrySet()) {
            String order = close.getKey().toString();
            for (int s = 0; s < streams.size(); s++) {
                if (terminals.get(s).no() == close.getValue()[0]) {
                    continue;
                }
                expected++;
                streams.get(s).received().stream()
                        .filter(r -> r.atNanos() >= close.getValue()[1])
                        .filter(r -> r.notice().hasNonNull("topic") && r.notice().hasNonNull("id")
                                && "ORDER".equals(r.notice().get("topic").asString())
                                && order.equals(r.notice().get("id").asString()))
                        .findFirst().ifPresent(r -> delays.add(r.atNanos() - close.getValue()[1]));
            }
        }
        int total = streams.stream().mapToInt(s -> s.received().size()).sum();
        return new FanOut(delays.stream().sorted().toList(), expected, total);
    }

    record FanOut(List<Long> sortedDelays, int expected, int noticesReceived) {
    }

    private Map<String, Map<String, Object>> rows() {
        Map<String, Map<String, Object>> rows = new TreeMap<>();
        latencies.forEach((operation, samples) -> {
            List<Long> sorted = samples.stream().sorted().toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("n", sorted.size());
            row.put("p50Ms", ms(sorted, 0.50));
            row.put("p95Ms", ms(sorted, 0.95));
            row.put("p99Ms", ms(sorted, 0.99));
            row.put("maxMs", sorted.getLast() / 1e6);
            row.put("targetP95Ms", TARGET_MS);
            row.put("meets", ms(sorted, 0.95) < TARGET_MS);
            rows.put(operation, row);
        });
        return rows;
    }

    private List<String> misses() {
        return rows().entrySet().stream().filter(e -> !(Boolean) e.getValue().get("meets")).map(Map.Entry::getKey)
                .toList();
    }

    private String report(Duration elapsed, FanOut fanOut) throws IOException {
        Runtime runtime = Runtime.getRuntime();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        String hardware = System.getProperty("os.name") + ' ' + System.getProperty("os.version") + ' '
                + System.getProperty("os.arch") + ", " + runtime.availableProcessors() + " CPUs, "
                + os.getTotalMemorySize() / (1024 * 1024 * 1024) + " GiB RAM, JVM max heap "
                + runtime.maxMemory() / (1024 * 1024) + " MiB, Java " + System.getProperty("java.version")
                + "; PostgreSQL in a local Docker container; clients and server on the same machine";
        double load = os.getSystemLoadAverage();
        Map<String, Long> volume = new LinkedHashMap<>();
        for (String table : List.of("catalog.item", "dining.restaurant_table", "orders.order_header",
                "orders.order_line", "settlement.payment", "settlement.refund", "settlement.bill_partition",
                "settlement.credit_charge", "fiscal.fiscal_document", "cash.cash_movement", "production.print_job",
                "shared.idempotency_record", "audit.audit_event", "reporting.sales_record")) {
            volume.put(table, count(table));
        }
        double seconds = elapsed.toNanos() / 1e9;
        Map<String, Map<String, Object>> rows = rows();
        List<String> misses = misses();

        StringBuilder out = new StringBuilder("\nR3 load smoke (spec §12, PRD S45, informational)\n");
        out.append("  hardware: ").append(hardware).append('\n');
        out.append(String.format("  system load average (1 min) at the end: %.2f%n", load));
        out.append("  load: ").append(TERMINALS).append(" POS terminals + 1 BackOffice reporter for ")
                .append(String.format("%.1f", seconds)).append(" s, ").append(requests.get()).append(" requests (")
                .append(String.format("%.1f", requests.get() / seconds)).append("/s), ").append(ordersClosed.get())
                .append(" orders closed (").append(String.format("%.2f", ordersClosed.get() / seconds))
                .append("/s); settlements ").append(new TreeMap<>(settlements)).append('\n');
        out.append("  errors: ").append(errors.size()).append(errors.isEmpty() ? "" : " " + errors).append('\n');
        out.append("  data volume at the end: ").append(volume).append('\n');
        out.append(String.format("  %-48s %6s %8s %8s %8s %8s  %s%n", "operation", "n", "p50 ms", "p95 ms",
                "p99 ms", "max ms", "§12 target"));
        rows.forEach((operation, row) -> out.append(String.format("  %-48s %6d %8.1f %8.1f %8.1f %8.1f  p95 < %d ms: %s%n",
                operation, (Integer) row.get("n"), (Double) row.get("p50Ms"), (Double) row.get("p95Ms"),
                (Double) row.get("p99Ms"), (Double) row.get("maxMs"), TARGET_MS,
                (Boolean) row.get("meets") ? "ok" : "MISS")));
        Map<String, Object> sse = new LinkedHashMap<>();
        sse.put("streams", TERMINALS);
        sse.put("noticesReceived", fanOut.noticesReceived());
        sse.put("closeNoticesExpected", fanOut.expected());
        sse.put("closeNoticesDelivered", fanOut.sortedDelays().size());
        if (!fanOut.sortedDelays().isEmpty()) {
            sse.put("p50Ms", ms(fanOut.sortedDelays(), 0.50));
            sse.put("p95Ms", ms(fanOut.sortedDelays(), 0.95));
            sse.put("p99Ms", ms(fanOut.sortedDelays(), 0.99));
        }
        out.append("  SSE (D60, informational): ").append(sse)
                .append(" — delay from a close's request start to its ORDER notice on each other terminal\n");
        out.append(String.format("  printers received: kitchen %d, bar %d, counter %d payloads (asynchronous, "
                + "excluded from §12)%n", KITCHEN.received().size(), BAR.received().size(), COUNTER.received().size()));
        out.append(misses.isEmpty() ? "  every operation meets the §12 target (p95 < 500 ms)\n"
                : "  " + misses.size() + " operation(s) MISS the §12 target: " + misses + "\n");

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("run", "R3LoadSmoke");
        json.put("at", Instant.now().toString());
        json.put("hardware", hardware);
        json.put("systemLoadAverage1m", load);
        json.put("terminals", TERMINALS);
        json.put("seconds", seconds);
        json.put("seed", SEED);
        json.put("requests", requests.get());
        json.put("requestsPerSecond", requests.get() / seconds);
        json.put("ordersClosed", ordersClosed.get());
        json.put("settlements", new TreeMap<>(settlements));
        json.put("errors", errors);
        json.put("volume", volume);
        json.put("operations", rows);
        json.put("sse", sse);
        json.put("misses", misses);
        Path dir = Path.of("target", "load-smoke");
        Files.createDirectories(dir);
        Path file = dir.resolve("load-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now()) + ".json");
        Files.writeString(file, Browser.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(json));
        out.append("  JSON report: ").append(file.toAbsolutePath()).append('\n');
        return out.toString();
    }

    private long count(String table) {
        try {
            return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
        } catch (RuntimeException missing) {
            return -1; // the table has another name in this schema: reported as -1, never a failure
        }
    }

    private UUID printer(String code, TcpFakePrinter device) throws Exception {
        return id(office.command(BO + "/printers", Map.of("storeId", store, "code", code, "name",
                "Impressora " + code, "host", device.host(), "port", device.port(), "codePage", "CP860"), 201,
                "printer " + code));
    }

    private static TcpFakePrinter open() {
        try {
            return TcpFakePrinter.accepting();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal money(JsonNode node) {
        return new BigDecimal(node.get("amount").asString());
    }

    /** Nearest-rank percentile, in milliseconds. */
    private static double ms(List<Long> sorted, double percentile) {
        int rank = (int) Math.ceil(percentile * sorted.size());
        return sorted.get(Math.max(0, rank - 1)) / 1e6;
    }
}
