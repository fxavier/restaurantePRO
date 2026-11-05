package com.xavier.backend.e2e;

import static com.xavier.backend.e2e.Installation.CASHIER_ROLE;
import static com.xavier.backend.e2e.Installation.id;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Latency smoke of spec §12 (informational, not a gate): 10 POS terminals, each with its own employee session and
 * open order, repeat simple local commands and queries against the real embedded server for a while, and the
 * p50/p95/p99 of each are printed with the hardware and the data volume. No printer is routed (physical printing
 * and external services are excluded by §12).
 *
 * <p><b>Not part of the normal suite, and never skipped:</b> the class name matches none of Surefire's default
 * include patterns ({@code Test*}, {@code *Test}, {@code *Tests}, {@code *TestCase}) and the POM does not change
 * them, so {@code mvn test} never discovers it (no "skipped" entry). Run it by name, which overrides the includes:
 * <pre>rtk mvn test -Dtest=R2LatencySmoke [-Dlatency.seconds=30] [-Dlatency.clients=10]</pre>
 * It fails only when a request fails (a non-2xx answer), never on a latency figure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Installation.FreshDatabase.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R2LatencySmoke {

    static final String INITIAL_PASSWORD = "initial " + UUID.randomUUID();
    static final String NEW_PASSWORD = "changed " + UUID.randomUUID();
    static final String PIN = "7391";
    static final String POS = "/api/v1/pos";
    static final int CLIENTS = Integer.getInteger("latency.clients", 10);
    static final Duration RUN = Duration.ofSeconds(Long.getLong("latency.seconds", 30));

    @DynamicPropertySource
    static void bootstrapSettings(DynamicPropertyRegistry registry) throws IOException {
        Installation.bootstrap(registry, "Restaurante Latency", INITIAL_PASSWORD);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    /** One terminal's view: its tab, its store and the order it works on. */
    record Terminal(Browser tab, UUID store, UUID order, UUID item) {
    }

    @Test
    void tenTerminalsDoSimpleLocalCommands() throws Exception {
        List<String> setup = new ArrayList<>();
        Browser office = new Browser("backoffice", port, setup);
        Installation.firstLogin(office, INITIAL_PASSWORD, NEW_PASSWORD);
        UUID store = Installation.store(office, "PERF");
        UUID area = id(office.post("/api/v1/backoffice/areas", Map.of("storeId", store, "code", "SALA",
                "name", "Sala"), 201));
        UUID item = Installation.publishCatalog(office, store).get("SUMO");
        List<Terminal> terminals = new ArrayList<>();
        for (int i = 1; i <= CLIENTS; i++) {
            UUID terminal = Installation.terminal(office, store, "POS" + i);
            String code = Installation.employee(office, store, "E" + i, "Empregado " + i, PIN, CASHIER_ROLE);
            UUID table = id(office.post("/api/v1/backoffice/tables", Map.of("storeId", store, "areaId", area,
                    "code", "M" + i, "seats", 4), 201));
            Browser tab = Installation.posLogin(office, terminal, code, PIN, "pos" + i);
            UUID order = id(tab.command(POS + "/tables/" + table + "/open", Map.of("guests", 2), 201, "open"));
            terminals.add(new Terminal(tab, store, order, item)); // timed sends record no journey
        }

        Map<String, List<Long>> latencies = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> runs = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(CLIENTS)) {
            for (Terminal terminal : terminals) {
                runs.add(pool.submit(work(terminal, start, latencies)));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> run : runs) {
                total += run.get();
            }
            report(total, latencies);
        }
    }

    private Callable<Integer> work(Terminal terminal, CountDownLatch start, Map<String, List<Long>> latencies) {
        return () -> {
            start.await();
            long until = System.nanoTime() + RUN.toNanos();
            int requests = 0;
            List<Long> tables = new ArrayList<>();
            List<Long> order = new ArrayList<>();
            List<Long> draft = new ArrayList<>();
            while (System.nanoTime() < until) {
                tables.add(timed(terminal.tab(), "GET", POS + "/tables?storeId=" + terminal.store(), null, Map.of()));
                order.add(timed(terminal.tab(), "GET", POS + "/orders/" + terminal.order(), null, Map.of()));
                draft.add(timed(terminal.tab(), "POST", POS + "/orders/" + terminal.order() + "/lines",
                        Map.of("itemId", terminal.item(), "quantity", BigDecimal.ONE), Browser.idem()));
                requests += 3;
            }
            latencies.computeIfAbsent("GET /pos/tables", k -> new CopyOnWriteArrayList<>())
                    .addAll(tables);
            latencies.computeIfAbsent("GET /pos/orders/{id}", k -> new CopyOnWriteArrayList<>())
                    .addAll(order);
            latencies.computeIfAbsent("POST /pos/orders/{id}/lines (draft)",
                    k -> new CopyOnWriteArrayList<>()).addAll(draft);
            return requests;
        };
    }

    private static long timed(Browser tab, String method, String path, Object body, Map<String, String> headers)
            throws Exception {
        long begin = System.nanoTime();
        HttpResponse<String> response = tab.send(method, path, body, Browser.Csrf.SEND, headers);
        long elapsed = System.nanoTime() - begin;
        assertThat(response.statusCode()).as("%s %s answered %s", method, path, response.body()).isBetween(200, 299);
        return elapsed;
    }

    private void report(int total, Map<String, List<Long>> latencies) {
        StringBuilder out = new StringBuilder("\nR2 latency smoke (spec §12, informational)\n");
        Runtime runtime = Runtime.getRuntime();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        out.append("  hardware: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(' ').append(System.getProperty("os.arch"))
                .append(", ").append(runtime.availableProcessors()).append(" CPUs, ")
                .append(os.getTotalMemorySize() / (1024 * 1024 * 1024)).append(" GiB RAM, JVM max heap ")
                .append(runtime.maxMemory() / (1024 * 1024)).append(" MiB, Java ")
                .append(System.getProperty("java.version")).append("; PostgreSQL in a local Docker container\n");
        out.append("  load: ").append(CLIENTS).append(" concurrent clients for ").append(RUN.toSeconds())
                .append(" s, ").append(total).append(" requests (").append(total / Math.max(1, RUN.toSeconds()))
                .append("/s)\n");
        Map<String, Long> volume = new LinkedHashMap<>();
        volume.put("orders.order_header", count("orders.order_header"));
        volume.put("orders.order_line", count("orders.order_line"));
        volume.put("dining.restaurant_table", count("dining.restaurant_table"));
        volume.put("shared.idempotency_record", count("shared.idempotency_record"));
        out.append("  data volume at the end: ").append(volume).append('\n');
        out.append("  note: each client drafts into its one open order for the whole run, so that order's lines (and"
                + " GET /pos/orders/{id}) grow from 0 to the final count per order\n");
        latencies.forEach((operation, samples) -> {
            List<Long> sorted = samples.stream().sorted().toList();
            out.append(String.format("  %-38s n=%6d  p50=%6.1f ms  p95=%6.1f ms  p99=%6.1f ms  max=%7.1f ms%n",
                    operation, sorted.size(), ms(sorted, 0.50), ms(sorted, 0.95), ms(sorted, 0.99),
                    sorted.getLast() / 1e6));
        });
        System.out.println(out);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    /** Nearest-rank percentile, in milliseconds. */
    private static double ms(List<Long> sorted, double percentile) {
        int rank = (int) Math.ceil(percentile * sorted.size());
        return sorted.get(Math.max(0, rank - 1)) / 1e6;
    }
}
