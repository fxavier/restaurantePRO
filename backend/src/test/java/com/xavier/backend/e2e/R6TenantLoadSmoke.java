package com.xavier.backend.e2e;

import static com.xavier.backend.e2e.Installation.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.config.security.TestJwtFactory;
import com.xavier.backend.tenancy.RecordingIdentityProvider;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The R6 load smoke (A92, carrying the RLS run's A77; spec §12: p95 &lt; 500 ms for simple local commands and reads,
 * printing and external services excluded) at <b>20 tenants × 10 POS terminals</b> on one central installation, the
 * real HTTP API and security chain (bearer tokens of the tenant and devices realms, {@code X-Pos-Session}) and the
 * pool as {@code restaurantpro_app}.
 *
 * <p><b>Seed</b> (not measured): each tenant onboarded by the platform API (D181), then through its administrator's
 * API: one store, 4 tables per terminal, a published catalog of {@code load.items} items at 16 % VAT, a kitchen
 * printer (jobs stay QUEUED: no bridge claims them, printing is excluded), a fiscal series, discount limits (15 %
 * free, published in a promotions release), and per terminal a register, a cashier and a terminal whose enrolment is written as the owner (as
 * {@link Installation#enrolled}; enrolment is not a §12 operation), its PIN login and an open cash session.
 *
 * <p><b>Measured cycle</b> per terminal, each request followed by a think time of {@code load.think-ms} (default
 * 1000 ms: a cashier's pace is slower; 200 terminals offer at most ~200 requests/s): read the floor map, the
 * catalog and the current promotions; open one of its tables; draft two lines; read the order; confirm the round (catalog and promotion versions, the previewed
 * gross, D103/D104); read it; a 10 % manual
 * discount on one line ({@code POST /pos/orders/{id}/discounts}, the A77 route); read the settlement; pay the due in
 * cash; close the order. The first terminal of each tenant also reads a 1-day sales report as the administrator
 * after each close. {@code CLOSE} issues the fiscal document through the fiscal adapter port (an external call, D48)
 * and is reported, not gated, as in {@link R3LoadSmoke}.
 *
 * <p><b>Three phases, one JVM and database, after a warm-up of {@code load.warmup-seconds}</b> (the method of the
 * no-RLS baseline, S76): a run without row-level security is correct only while one company has data, because the
 * tenant predicate of tenant-wide tables is RLS's alone (D179: e.g. the CASH payment method is found by its code; with
 * three tenants and {@code BYPASSRLS} that lookup returns three rows and the payment fails). So:
 * <ol>
 *   <li><b>P1, baseline without RLS</b>: the bootstrap tenant is the only company with data (1 × {@code
 *       load.terminals}); the superuser grants {@code restaurantpro_app} {@code BYPASSRLS} after startup and the pool
 *       is evicted (a probe proves the pool's connections bypass RLS).</li>
 *   <li><b>P2, RLS on the same data and load</b> ({@code NOBYPASSRLS}, evicted again, probed): P2 − P1 is the cost
 *       of the tenant and store policies; P2 is also the RLS run's A77 configuration (10 terminals, one company).</li>
 *   <li><b>P3, the A92 target</b>: {@code load.tenants − 1} more tenants onboarded by the platform API and seeded
 *       the same way, then every terminal of every tenant at once, RLS on.</li>
 * </ol>
 * Each phase runs {@code load.seconds}. The p50/p95/p99/max per operation of each phase, P2 − P1 at p95, the
 * throughput, the hardware and the row counts are printed and written to {@code target/load-smoke/r6-load-<UTC>.json}.
 *
 * <p>Not part of the gate and never skipped: the name matches none of Surefire's include patterns. Run it by name:
 * <pre>rtk mvn test -Dtest=R6TenantLoadSmoke [-Dload.tenants=20] [-Dload.terminals=10] [-Dload.seconds=120]
 *     [-Dload.items=100] [-Dload.think-ms=1000] [-Dload.gate=true]</pre>
 * The test fails on any failed request and, only with {@code -Dload.gate=true}, on a gated p95 of the RLS phase at or
 * above 500 ms.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({Installation.FreshDatabase.class, RecordingIdentityProvider.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R6TenantLoadSmoke {

    static final String BO = "/api/v1/backoffice";
    static final String POS = "/api/v1/pos";
    static final int TENANTS = Integer.getInteger("load.tenants", 20);
    static final int TERMINALS = Integer.getInteger("load.terminals", 10);
    static final int ITEMS = Integer.getInteger("load.items", 100);
    static final long THINK_MS = Long.getLong("load.think-ms", 1000);
    static final Duration RUN = Duration.ofSeconds(Long.getLong("load.seconds", 120));
    static final Duration WARM_UP = Duration.ofSeconds(Long.getLong("load.warmup-seconds", 30));
    static final boolean GATE = Boolean.getBoolean("load.gate");
    static final long TARGET_MS = 500;
    static final long SEED = 92;
    static final String PIN = "7391";
    static final List<String> REPORTED_ONLY = List.of("CLOSE");
    static final String TAG = UUID.randomUUID().toString().substring(0, 6);

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) throws IOException {
        Installation.bootstrap(registry, "Plataforma carga", "unused");
        registry.add("restaurantpro.security.issuers.devices", () -> TestJwtFactory.DEVICES_ISSUER);
        registry.add("restaurantpro.test.print-bridge", () -> "false");
        registry.add("logging.level.root", () -> "WARN");
    }

    @Autowired
    PostgreSQLContainer freshPostgres;
    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager transactions;
    @LocalServerPort
    int port;

    private final List<String> journey = Collections.synchronizedList(new ArrayList<>());

    record Terminal(int tenant, int no, Browser pos, UUID store, List<UUID> tables, List<UUID> items,
                    Browser office, Random random) {
    }

    /** One phase's samples: operation → latencies in ns; errors. */
    static final class Phase {
        final String name;
        final Map<String, List<Long>> samples = new ConcurrentHashMap<>();
        final AtomicInteger errors = new AtomicInteger();
        final List<String> firstErrors = new CopyOnWriteArrayList<>();
        final AtomicInteger cycles = new AtomicInteger();
        long elapsedNanos;
        /** Diagnostics sampled every 2 s while the phase runs (never asserted). */
        final List<String> diagnosis = new ArrayList<>();

        Phase(String name) {
            this.name = name;
        }

        void add(String operation, long nanos) {
            samples.computeIfAbsent(operation, ignored -> Collections.synchronizedList(new ArrayList<>())).add(nanos);
        }
    }

    @Test
    void twentyTenantsOfTenTerminalsMeetTheP95TargetAgainstANoRlsBaseline() throws Exception {
        JdbcClient owner = Installation.owner(freshPostgres);
        JdbcClient superuser = JdbcClient.create(new DriverManagerDataSource(freshPostgres.getJdbcUrl(),
                freshPostgres.getUsername(), freshPostgres.getPassword()));
        Browser platform = new Browser("platform", port, journey).withFixtures(owner, Instant::now)
                .signedIn(() -> TestJwtFactory.support("operator-" + TAG, Instant.now()).claim("realm_access",
                        Map.of("roles", List.of("platform-operator"))).encode());

        // P1 and P2: the bootstrap tenant as the only company with data (a no-RLS run is correct only then: the
        // tenant predicate of tenant-wide tables is RLS's alone, D179, e.g. the CASH payment method by code)
        Instant seedStart = Instant.now();
        Browser bootstrapAdmin = new Browser("admin 1", port, journey);
        Installation.firstLogin(bootstrapAdmin, owner);
        List<Terminal> first = seed(bootstrapAdmin, owner, 1);
        Duration seedOne = Duration.between(seedStart, Instant.now());

        superuser.sql("ALTER DATABASE " + freshPostgres.getDatabaseName() + " SET track_functions = 'all'").update();
        evictPool();
        run("warm-up (not reported)", first, WARM_UP);
        superuser.sql("ALTER ROLE restaurantpro_app BYPASSRLS").update();
        evictPool();
        assertThat(applicationRoleBypassesRls()).as("P1: the pool's connections bypass RLS").isTrue();
        Phase baseline = run("P1 baseline, no RLS (restaurantpro_app BYPASSRLS), 1 tenant x " + TERMINALS, first);

        superuser.sql("ALTER ROLE restaurantpro_app NOBYPASSRLS").update();
        evictPool();
        assertThat(applicationRoleBypassesRls()).as("P2: RLS binds the pool again").isFalse();
        Phase rlsOne = run("P2 RLS, 1 tenant x " + TERMINALS, first);

        // P3: 19 more tenants onboarded by the platform API, then every terminal of the 20 at once
        Instant moreStart = Instant.now();
        List<Terminal> terminals = Collections.synchronizedList(new ArrayList<>(first));
        try (ExecutorService seeders = Executors.newFixedThreadPool(4)) {
            List<Future<List<Terminal>>> seeded = new ArrayList<>();
            for (int t = 2; t <= TENANTS; t++) {
                int tenant = t;
                seeded.add(seeders.submit(() -> seed(onboard(platform, owner, tenant), owner, tenant)));
            }
            for (Future<List<Terminal>> tenant : seeded) {
                terminals.addAll(tenant.get());
            }
        }
        Duration seedTime = seedOne.plus(Duration.between(moreStart, Instant.now()));
        Phase rls = run("P3 RLS, " + TENANTS + " tenants x " + TERMINALS, terminals);

        String report = report(seedTime, baseline, rlsOne, rls);
        System.out.println(report);
        Path dir = Path.of("target", "load-smoke");
        Files.createDirectories(dir);
        Path file = dir.resolve("r6-load-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now()) + ".json");
        Files.writeString(file, json(seedTime, baseline, rlsOne, rls));
        System.out.println("R6 load smoke: written " + file.toAbsolutePath());

        for (Phase phase : List.of(baseline, rlsOne, rls)) {
            assertThat(phase.errors.get()).as("%s errors: %s", phase.name, phase.firstErrors).isZero();
        }
        if (GATE) {
            List<String> misses = new ArrayList<>();
            rls.samples.forEach((operation, nanos) -> {
                if (!REPORTED_ONLY.contains(operation) && p(nanos, 95) >= TARGET_MS) {
                    misses.add(operation + " p95 " + p(nanos, 95) + " ms");
                }
            });
            assertThat(misses).as("§12 gate (P3)").isEmpty();
        }
    }

    // ---- seed ----

    /** D181: the platform onboards tenant {@code no}; @return its administrator's tab once it is ACTIVE */
    private Browser onboard(Browser platform, JdbcClient owner, int no) throws Exception {
        String code = "load-" + no + "-" + TAG;
        String email = "admin@" + code + ".example";
        UUID tenant = id(platform.post("/api/v1/platform/tenants", Map.of("code", code, "legalName",
                "Restaurante " + no + " Lda", "taxId", "400000002", "country", "MZ", "currency", "MZN",
                "firstStoreName", "Loja Central", "adminEmail", email), 202, "onboard " + no));
        Awaitility.await().atMost(Duration.ofMinutes(2)).until(() -> "ACTIVE".equals(owner.sql(
                "SELECT status FROM tenancy.tenant WHERE id = ?").param(tenant).query(String.class).single()));
        return new Browser("admin " + no, port, journey).withFixtures(owner, Instant::now)
                .signedIn(() -> TestJwtFactory.account("user-" + email).encode());
    }

    /** The store, catalog, terminals and cashiers of tenant {@code no}, through its administrator {@code office}. */
    private List<Terminal> seed(Browser office, JdbcClient owner, int no) throws Exception {
        UUID cashierRole = role(office, owner, Installation.CASHIER_ROLE);

        UUID store = Installation.store(office, "1");
        UUID area = id(office.post(BO + "/areas", Map.of("storeId", store, "code", "SALA", "name", "Sala"), 201));
        List<UUID> tables = new ArrayList<>();
        for (int i = 1; i <= 4 * TERMINALS; i++) {
            tables.add(id(office.post(BO + "/tables", Map.of("storeId", store, "areaId", area, "code", "M" + i,
                    "seats", 4), 201)));
        }
        List<UUID> items = catalog(office, store, new Random(SEED));
        UUID printer = id(office.command(BO + "/printers", Map.of("storeId", store, "code", "COZ", "name",
                "Cozinha", "host", "192.168.1.50", "port", 9100, "codePage", "CP860"), 201, "printer"));
        office.put(BO + "/production-routes", Map.of("storeId", store, "routes", List.of(
                Map.of("destinationCode", "COZINHA", "printerId", printer))), 200, "routes");
        office.command(BO + "/fiscal/series", Map.of("storeId", store, "code", "FR", "firstNumber", 1), 201,
                "series");
        office.put(BO + "/promotions/discount-limits/" + store, Map.of("freeLimitPercent", new BigDecimal("15"),
                "maxPercent", new BigDecimal("50")), 200, "discount limits");
        // the limits apply once a promotions release of the store is published (as in R4)
        UUID promotions = id(office.command(BO + "/promotions/releases", Map.of("storeId", store), 201,
                "promotion release"));
        office.command(BO + "/promotions/releases/" + promotions + "/validate", Map.of(), 200, "validate");
        office.command(BO + "/promotions/releases/" + promotions + "/publish", Map.of(), 200, "publish");

        List<Terminal> terminals = new ArrayList<>();
        for (int i = 1; i <= TERMINALS; i++) {
            UUID register = id(office.command(BO + "/cash/registers", Map.of("storeId", store, "code", "CX" + i,
                    "name", "Caixa " + i), 201, "register"));
            String cashier = Installation.employee(office, store, "CX" + i, "Caixa " + i, PIN, cashierRole);
            UUID terminal = id(office.command(BO + "/terminals", Map.of("storeId", store, "code", "POS" + i, "name",
                    "POS" + i), 201, "register the terminal"));
            Supplier<String> tokens = Installation.enrolled(office, terminal);
            Browser pos = new Browser("pos " + no + "." + i, port, journey).withFixtures(owner, Instant::now)
                    .enrolled(tokens);
            pos.posSession(pos.post("/api/v1/auth/pos-login", Map.of("employeeCode", cashier, "pin", PIN), 200,
                    Browser.Csrf.SEND, pos.terminalAuthorization(), "pos-login").get("posSession").asString());
            pos.command(POS + "/cash/sessions", Map.of("registerId", register, "openingFloat",
                    new BigDecimal("100.00")), 201, "open session");
            terminals.add(new Terminal(no, i, pos, store, tables.subList(4 * (i - 1), 4 * i), items, office,
                    new Random(SEED * 1000 + no * 100L + i)));
        }
        return terminals;
    }

    /** {@link #ITEMS} kitchen items at 16 % VAT, 5.00–54.00, published for {@code store}. */
    private static List<UUID> catalog(Browser office, UUID store, Random random) throws Exception {
        String catalog = Installation.CATALOG;
        UUID family = id(office.post(catalog + "/families", Map.of("code", "REST", "name", "Restaurante"), 201));
        UUID unit = id(office.post(catalog + "/units", Map.of("code", "UN", "name", "Unidade"), 201));
        UUID tax = id(office.post(catalog + "/tax-categories", Map.of("code", "IVA16", "name", "IVA"), 201));
        office.post(catalog + "/tax-categories/" + tax + "/rates",
                Map.of("ratePercent", new BigDecimal("16"), "effectiveFrom", "2000-01-01T00:00:00Z"), 201);
        UUID priceList = id(office.post(catalog + "/price-lists", Map.of("storeId", store, "name", "Sala"), 201));
        List<UUID> items = new ArrayList<>();
        for (int i = 1; i <= ITEMS; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", "IT%03d".formatted(i));
            item.put("name", "Prato " + i);
            item.put("familyId", family);
            item.put("unitId", unit);
            item.put("taxCategoryId", tax);
            item.put("productionDestination", "COZINHA");
            item.put("soldByWeight", false);
            UUID itemId = id(office.post(catalog + "/items", item, 201));
            office.post(catalog + "/price-lists/" + priceList + "/prices", Map.of("itemId", itemId,
                    "amount", new BigDecimal(5 + random.nextInt(50) + ".00")), 201);
            items.add(itemId);
        }
        UUID release = id(office.post(catalog + "/releases", Map.of("storeId", store), 201));
        office.post(catalog + "/releases/" + release + "/validate", null, 200);
        office.command(catalog + "/releases/" + release + "/publish", Map.of("note", "carga"), 200, "publish");
        return items;
    }

    private static UUID role(Browser office, JdbcClient owner, UUID bootstrapRole) throws Exception {
        String name = owner.sql("SELECT name FROM identity.role WHERE id = ?").param(bootstrapRole)
                .query(String.class).single();
        JsonNode roles = office.get(BO + "/roles", 200);
        for (JsonNode role : roles.isArray() ? roles : roles.get("content")) {
            if (name.equals(role.get("name").asString())) {
                return id(role);
            }
        }
        throw new AssertionError("no role " + name);
    }

    // ---- phases ----

    private void evictPool() throws java.sql.SQLException {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        hikari.getHikariPoolMXBean().softEvictConnections();
    }

    /** Whether a connection of the application's pool now bypasses row-level security. */
    private boolean applicationRoleBypassesRls() {
        return Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(status -> jdbc.sql(
                "SELECT rolbypassrls FROM pg_catalog.pg_roles WHERE rolname = current_user").query(Boolean.class)
                .single()));
    }

    private Phase run(String name, List<Terminal> terminals) throws Exception {
        return run(name, terminals, RUN);
    }

    private Phase run(String name, List<Terminal> terminals, Duration length) throws Exception {
        Phase phase = new Phase(name);
        CountDownLatch start = new CountDownLatch(1);
        long[] deadline = new long[1];
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = new ArrayList<>();
            for (Terminal terminal : terminals) {
                running.add(pool.submit(() -> {
                    start.await();
                    int table = 0;
                    while (System.nanoTime() < deadline[0]) {
                        try {
                            cycle(phase, terminal, terminal.tables().get(table++ % terminal.tables().size()));
                            phase.cycles.incrementAndGet();
                        } catch (Exception | AssertionError e) {
                            phase.errors.incrementAndGet();
                            if (phase.firstErrors.size() < 5) {
                                phase.firstErrors.add(terminal.pos().name() + ": " + e.getMessage());
                            }
                        }
                    }
                    return null;
                }));
            }
            Map<String, Long> idleStart = databaseCounters();
            long idleBegan = System.nanoTime();
            Thread.sleep(10_000); // an idle window: the background jobs' own transaction rate (diagnostics)
            Map<String, Long> before = databaseCounters();
            double backgroundPerSecond = (before.get("xacts") - idleStart.get("xacts"))
                    / ((System.nanoTime() - idleBegan) / 1e9);
            Sampler sampler = new Sampler();
            Thread sampling = Thread.ofPlatform().daemon().start(sampler);
            long began = System.nanoTime();
            deadline[0] = began + length.toNanos();
            start.countDown();
            for (Future<?> terminal : running) {
                terminal.get();
            }
            phase.elapsedNanos = System.nanoTime() - began;
            sampler.stop = true;
            sampling.join();
            Map<String, Long> after = databaseCounters();
            long requests = phase.samples.values().stream().mapToLong(List::size).sum()
                    + phase.cycles.get(); // + the unmeasured settlement read before each close
            phase.diagnosis.add(sampler.summary());
            long xacts = after.get("xacts") - before.get("xacts");
            double background = backgroundPerSecond * phase.elapsedNanos / 1e9;
            phase.diagnosis.add(("background jobs idle: %.1f transactions/s; per HTTP request after removing them: "
                    + "%.2f transactions").formatted(backgroundPerSecond, (xacts - background) / requests));
            phase.diagnosis.add("database per HTTP request: %.2f transactions (all), %.2f resolve_membership calls, %.1f ms DB time in it (xacts %d, requests ~%d)"
                    .formatted(xacts / (double) requests,
                            (after.get("resolveCalls") - before.get("resolveCalls")) / (double) requests,
                            (after.get("resolveMs") - before.get("resolveMs")) / (double) requests,
                            after.get("xacts") - before.get("xacts"), requests));
        }
        return phase;
    }

    /** Transactions of the database and calls of tenancy.resolve_membership (track_functions = all), as the superuser. */
    private Map<String, Long> databaseCounters() {
        JdbcClient superuser = superuser();
        superuser.sql("SELECT pg_stat_force_next_flush()").query().listOfRows();
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("xacts", superuser.sql("SELECT xact_commit + xact_rollback FROM pg_stat_database "
                + "WHERE datname = current_database()").query(Long.class).single());
        Map<String, Object> resolve = superuser.sql("""
                SELECT coalesce(sum(calls), 0) AS calls, coalesce(sum(total_time), 0)::bigint AS ms
                FROM pg_stat_user_functions WHERE schemaname = 'tenancy' AND funcname = 'resolve_membership'""")
                .query().singleRow();
        counters.put("resolveCalls", ((Number) resolve.get("calls")).longValue());
        counters.put("resolveMs", ((Number) resolve.get("ms")).longValue());
        return counters;
    }

    private JdbcClient superuser() {
        return JdbcClient.create(new DriverManagerDataSource(freshPostgres.getJdbcUrl(), freshPostgres.getUsername(),
                freshPostgres.getPassword()));
    }

    /** Every 2 s: the pool (active, idle, pending), the application's backends by state and wait event, CPU, load. */
    private final class Sampler implements Runnable {
        volatile boolean stop;
        final List<int[]> pool = new ArrayList<>();
        final Map<String, Integer> waits = new java.util.TreeMap<>();
        final List<double[]> cpu = new ArrayList<>();
        int samples;

        @Override
        public void run() {
            HikariDataSource hikari;
            try {
                hikari = dataSource.unwrap(HikariDataSource.class);
            } catch (java.sql.SQLException e) {
                return;
            }
            JdbcClient superuser = superuser();
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            while (!stop) {
                var bean = hikari.getHikariPoolMXBean();
                pool.add(new int[] {bean.getActiveConnections(), bean.getIdleConnections(),
                        bean.getThreadsAwaitingConnection()});
                superuser.sql("""
                        SELECT state || ':' || coalesce(wait_event_type || '/' || wait_event, 'CPU') AS w, count(*) AS n
                        FROM pg_stat_activity WHERE datname = current_database() AND usename = 'restaurantpro_app'
                          AND state <> 'idle' GROUP BY 1""").query().listOfRows().forEach(row -> waits.merge(
                        (String) row.get("w"), ((Number) row.get("n")).intValue(), Integer::sum));
                cpu.add(new double[] {os.getProcessCpuLoad(), os.getCpuLoad(), os.getSystemLoadAverage()});
                samples++;
                try {
                    Thread.sleep(2000); // the sampling period (diagnostics, not a wait for a result)
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        String summary() {
            double active = pool.stream().mapToInt(a -> a[0]).average().orElse(0);
            int maxActive = pool.stream().mapToInt(a -> a[0]).max().orElse(0);
            double pending = pool.stream().mapToInt(a -> a[2]).average().orElse(0);
            int maxPending = pool.stream().mapToInt(a -> a[2]).max().orElse(0);
            double jvm = cpu.stream().mapToDouble(c -> c[0]).average().orElse(0) * 100;
            double system = cpu.stream().mapToDouble(c -> c[1]).average().orElse(0) * 100;
            double load = cpu.stream().mapToDouble(c -> c[2]).average().orElse(0);
            double maxLoad = cpu.stream().mapToDouble(c -> c[2]).max().orElse(0);
            StringBuilder top = new StringBuilder();
            waits.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(6)
                    .forEach(e -> top.append(e.getKey()).append('=')
                            .append("%.1f".formatted(e.getValue() / (double) Math.max(1, samples))).append(' '));
            return ("%d samples: pool active %.1f (max %d) / pending %.1f (max %d); JVM CPU %.0f%%, machine CPU %.0f%%, "
                    + "load average %.1f (max %.1f); non-idle app backends per sample by state:wait: %s").formatted(
                    samples, active, maxActive, pending, maxPending, jvm, system, load, maxLoad, top.toString().trim());
        }
    }

    private void cycle(Phase phase, Terminal t, UUID table) throws Exception {
        Browser pos = t.pos();
        timed(phase, pos, "TABLES", "GET", POS + "/tables?storeId=" + t.store(), null, Map.of(), 200);
        long catalogVersion = timed(phase, pos, "CATALOG", "GET", POS + "/catalog?storeId=" + t.store(), null,
                Map.of(), 200).get("version").asLong();
        long promotionVersion = timed(phase, pos, "PROMOTIONS", "GET", POS + "/promotions/current", null, Map.of(),
                200).get("version").asLong();
        UUID order = id(timed(phase, pos, "OPEN", "POST", POS + "/tables/" + table + "/open", Map.of("guests", 2),
                Browser.idem(), 201));
        List<UUID> lines = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            UUID item = t.items().get(t.random().nextInt(t.items().size()));
            lines.add(id(timed(phase, pos, "LINE", "POST", POS + "/orders/" + order + "/lines", Map.of("itemId", item,
                    "quantity", new BigDecimal(1 + t.random().nextInt(2))), Browser.idem(), 201)));
        }
        JsonNode drafted = timed(phase, pos, "ORDER", "GET", POS + "/orders/" + order, null, Map.of(), 200);
        BigDecimal expectedGross = BigDecimal.ZERO;
        for (JsonNode line : drafted.get("lines")) {
            if ("DRAFT".equals(line.get("status").asString())) {
                expectedGross = expectedGross.add(new BigDecimal(line.get("gross").get("amount").asString()));
            }
        }
        timed(phase, pos, "CONFIRM", "POST", POS + "/orders/" + order + "/batches", Map.of("orderVersion",
                drafted.get("version").asLong(), "catalogVersion", catalogVersion, "lineIds", lines,
                "promotionVersion", promotionVersion, "expectedGross", expectedGross), Browser.idem(), 201);
        long version;
        version = timed(phase, pos, "ORDER", "GET", POS + "/orders/" + order, null, Map.of(), 200)
                .get("version").asLong();
        timed(phase, pos, "DISCOUNT", "POST", POS + "/orders/" + order + "/discounts", Map.of("orderVersion",
                version, "kind", "DISCOUNT", "percent", new BigDecimal("10"), "reason", "Carga", "lines",
                List.of(Map.of("lineId", lines.getFirst()))), Browser.idem(), 201);
        JsonNode settlement = timed(phase, pos, "SETTLEMENT", "GET", POS + "/orders/" + order + "/settlement", null,
                Map.of(), 200);
        JsonNode dueNode = settlement.get("due");
        BigDecimal due = new BigDecimal((dueNode.has("amount") ? dueNode.get("amount") : dueNode).asString());
        timed(phase, pos, "PAY", "POST", POS + "/payment-attempts", Map.of("orderId", order, "methodCode", "CASH",
                "amount", due, "tendered", due), Browser.idem(), 201);
        long orderVersion = pos.get(POS + "/orders/" + order + "/settlement", 200).get("orderVersion").asLong();
        timed(phase, pos, "CLOSE", "POST", POS + "/orders/" + order + "/close", Map.of("orderVersion", orderVersion),
                Browser.idem(), 200);
        if (t.no() == 1) {
            LocalDate today = LocalDate.now(ZoneId.of(Installation.ZONE));
            timed(phase, t.office(), "REPORT", "GET", BO + "/reports/sales?storeId=" + t.store() + "&from="
                    + today.minusDays(1) + "&to=" + today.plusDays(1) + "&groupBy=item", null, Map.of(), 200);
        }
    }

    /** One measured request, then the think time; fails on another status. */
    private static JsonNode timed(Phase phase, Browser tab, String operation, String method, String path, Object body,
                                  Map<String, String> headers, int expected) throws Exception {
        long began = System.nanoTime();
        HttpResponse<String> response = tab.send(method, path, body, Browser.Csrf.SEND, headers);
        long took = System.nanoTime() - began;
        if (response.statusCode() != expected && !(expected == 201 && response.statusCode() == 200)) {
            throw new AssertionError(method + " " + path + " -> " + response.statusCode() + " " + response.body());
        }
        phase.add(operation, took);
        if (THINK_MS > 0) {
            Thread.sleep(THINK_MS); // the cashier's pace between two requests (load model, not a wait for a result)
        }
        return response.body().isBlank() ? Browser.JSON.nullNode() : Browser.JSON.readTree(response.body());
    }

    // ---- report ----

    /** Nearest-rank percentile in milliseconds. */
    static long p(List<Long> nanos, int percentile) {
        List<Long> sorted;
        synchronized (nanos) {
            sorted = new ArrayList<>(nanos);
        }
        Collections.sort(sorted);
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        return Math.round(sorted.get(Math.max(0, rank - 1)) / 1_000_000.0);
    }

    private String report(Duration seed, Phase p1, Phase p2, Phase p3) {
        StringBuilder out = new StringBuilder();
        out.append("\nR6 load smoke (A92 / RLS A77): ").append(TENANTS).append(" tenants x ").append(TERMINALS)
                .append(" terminals, ").append(RUN.toSeconds()).append(" s per phase, think ").append(THINK_MS)
                .append(" ms, ").append(ITEMS).append(" items per tenant\n").append(hardware()).append('\n');
        out.append("p95 ms per operation; P1 no RLS 1x%d | P2 RLS 1x%d | P3 RLS %dx%d (n, p50/p95/p99/max)%n"
                .formatted(TERMINALS, TERMINALS, TENANTS, TERMINALS));
        out.append("%-11s | %-22s | %-22s | %-27s | %6s | %s%n".formatted("operation", "P1 p50/p95/p99/max",
                "P2 p50/p95/p99/max", "P3 n p50/p95/p99/max", "P2-P1", "verdict(P3)"));
        for (String operation : List.of("TABLES", "CATALOG", "PROMOTIONS", "OPEN", "LINE", "ORDER", "CONFIRM",
                "DISCOUNT", "SETTLEMENT", "PAY", "REPORT", "CLOSE")) {
            List<Long> a1 = p1.samples.getOrDefault(operation, List.of());
            List<Long> a2 = p2.samples.getOrDefault(operation, List.of());
            List<Long> a3 = p3.samples.getOrDefault(operation, List.of());
            if (a1.isEmpty() || a2.isEmpty() || a3.isEmpty()) {
                continue;
            }
            long p95 = p(a3, 95);
            String verdict = REPORTED_ONLY.contains(operation) ? "reported (fiscal adapter)"
                    : p95 < TARGET_MS ? "PASS" : "MISS";
            out.append("%-11s | %-22s | %-22s | %6d %-20s | %+6d | %s%n".formatted(operation, quartet(a1),
                    quartet(a2), a3.size(), quartet(a3), p(a2, 95) - p(a1, 95), verdict));
        }
        for (Phase phase : List.of(p1, p2, p3)) {
            phase.diagnosis.forEach(line -> out.append("  [").append(phase.name).append("] ").append(line).append('\n'));
        }
        for (Phase phase : List.of(p1, p2, p3)) {
            out.append("%s: %d cycles, %.1f measured requests/s, %d errors%n".formatted(phase.name,
                    phase.cycles.get(), phase.samples.values().stream().mapToInt(List::size).sum()
                            / (phase.elapsedNanos / 1e9), phase.errors.get()));
        }
        out.append("seed ").append(seed.toSeconds()).append(" s; rows at the end: ").append(volume()).append('\n');
        return out.toString();
    }

    private static String quartet(List<Long> nanos) {
        return p(nanos, 50) + "/" + p(nanos, 95) + "/" + p(nanos, 99) + "/" + p(nanos, 100);
    }

    private String json(Duration seed, Phase... phases) {
        StringBuilder json = new StringBuilder("{\"tenants\":" + TENANTS + ",\"terminals\":" + TERMINALS
                + ",\"seconds\":" + RUN.toSeconds() + ",\"thinkMs\":" + THINK_MS + ",\"items\":" + ITEMS
                + ",\"seedSeconds\":" + seed.toSeconds() + ",\"hardware\":\"" + hardware().replace("\"", "'")
                + "\",\"volume\":\"" + volume() + "\",\"phases\":[");
        List<String> rendered = new ArrayList<>();
        for (Phase phase : phases) {
            List<String> operations = new ArrayList<>();
            phase.samples.forEach((operation, nanos) -> operations.add("\"%s\":{\"n\":%d,\"p50\":%d,\"p95\":%d,"
                    .formatted(operation, nanos.size(), p(nanos, 50), p(nanos, 95)) + "\"p99\":%d,\"max\":%d}"
                    .formatted(p(nanos, 99), p(nanos, 100))));
            rendered.add("{\"name\":\"" + phase.name + "\",\"errors\":" + phase.errors.get() + ",\"cycles\":"
                    + phase.cycles.get() + ",\"operations\":{" + String.join(",", operations) + "}}");
        }
        return json.append(String.join(",", rendered)).append("]}").toString();
    }

    private static String hardware() {
        Runtime runtime = Runtime.getRuntime();
        return "%s %s, %d cores, JVM max heap %d MB, %s; server, PostgreSQL (Testcontainers) and clients on one machine"
                .formatted(System.getProperty("os.name"), System.getProperty("os.version"),
                        runtime.availableProcessors(), runtime.maxMemory() / (1024 * 1024),
                        ManagementFactory.getRuntimeMXBean().getVmName() + " " + System.getProperty("java.version"));
    }

    private String volume() {
        JdbcClient owner = Installation.owner(freshPostgres);
        List<String> counts = new ArrayList<>();
        for (String table : List.of("tenancy.tenant", "organization.store", "catalog.item", "orders.customer_order",
                "orders.order_line", "fiscal.fiscal_document", "production.print_job")) {
            try {
                counts.add(table + "=" + owner.sql("SELECT count(*) FROM " + table).query(Long.class).single());
            } catch (RuntimeException e) {
                counts.add(table + "=?");
            }
        }
        return String.join(", ", counts);
    }
}
