package com.xavier.backend.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
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
 * The report benchmark of D86 (informational, not a gate, like {@link R2LatencySmoke}): one store-year of reporting
 * facts (about 150 k documents and 600 k lines, two VAT rates, receipts, credit and voids) is seeded straight into the
 * reporting projection, then each report is requested through the real HTTP API for random 31-day and 1-day ranges.
 * The p50/p95/p99 are printed against the D86 targets (31 days: p95 &lt; 1 s; 1 day: p95 &lt; 300 ms) with the hardware
 * and the data volume. <b>A miss is reported as MISS</b>: it calls for the rollup follow-up slice D86 names, never for
 * a silent pass.
 *
 * <p><b>Not part of the normal suite, and never skipped:</b> the class name matches none of Surefire's default
 * include patterns, so {@code mvn test} never discovers it. Run it by name:
 * <pre>rtk mvn test -Dtest=R3ReportBenchmark [-Dbenchmark.documents=150000] [-Dbenchmark.samples=40]</pre>
 * It fails only when a request fails (a non-2xx answer), never on a latency figure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Installation.FreshDatabase.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R3ReportBenchmark {

    static final String INITIAL_PASSWORD = "initial " + UUID.randomUUID();
    static final String NEW_PASSWORD = "changed " + UUID.randomUUID();
    static final int DOCUMENTS = Integer.getInteger("benchmark.documents", 150_000);
    static final int SAMPLES = Integer.getInteger("benchmark.samples", 40);
    static final int WARMUP = 5;
    static final LocalDate FIRST_DAY = LocalDate.of(2025, 11, 1);
    static final int DAYS = 365;
    static final long TARGET_31_DAYS_MS = 1000;
    static final long TARGET_1_DAY_MS = 300;

    @DynamicPropertySource
    static void bootstrapSettings(DynamicPropertyRegistry registry) throws IOException {
        Installation.bootstrap(registry, "Restaurante Benchmark", INITIAL_PASSWORD);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Test
    void oneStoreYearOfReports() throws Exception {
        List<String> setup = new ArrayList<>();
        Browser office = new Browser("backoffice", port, setup);
        Installation.firstLogin(office, INITIAL_PASSWORD, NEW_PASSWORD);
        UUID store = Installation.store(office, "BENCH");
        long seeding = System.nanoTime();
        seed(store);
        Duration seeded = Duration.ofNanos(System.nanoTime() - seeding);

        Map<String, List<Long>> latencies = new LinkedHashMap<>();
        Random random = new Random(86);
        String[] reports = {"sales?groupBy=day", "sales?groupBy=hour", "sales?groupBy=item", "sales?groupBy=employee",
                "sales?groupBy=area", "sales?groupBy=family", "sales?groupBy=mode", "taxes?", "top-items?by=gross",
                "receipts?groupBy=method", "receipts?groupBy=day", "voids?groupBy=reason"};
        for (int days : new int[] {31, 1}) {
            for (String report : reports) {
                String name = "GET /reports/" + report.replace("?", " ").strip() + " (" + days + " d)";
                List<Long> samples = new ArrayList<>();
                for (int i = 0; i < WARMUP + SAMPLES; i++) {
                    LocalDate from = FIRST_DAY.plusDays(random.nextInt(DAYS - days + 1));
                    String path = "/api/v1/backoffice/reports/" + report + (report.endsWith("?") ? "" : "&")
                            + "storeId=" + store + "&from=" + from + "&to=" + from.plusDays(days - 1);
                    long begin = System.nanoTime();
                    HttpResponse<String> response = office.send("GET", path, null, Browser.Csrf.OMIT, Map.of());
                    long elapsed = System.nanoTime() - begin;
                    assertThat(response.statusCode()).as("%s answered %s", path, response.body()).isEqualTo(200);
                    if (i >= WARMUP) {
                        samples.add(elapsed);
                    }
                }
                latencies.put(name, samples);
            }
        }
        report(seeded, latencies);
    }

    /** One store-year: {@link #DOCUMENTS} documents of 4 lines (lines 1–3 at 16 %, line 4 at 23 %), deterministic. */
    private void seed(UUID store) {
        jdbc.sql("""
                CREATE TEMPORARY TABLE bench_line AS
                SELECT md5('doc' || d)::uuid AS document_id, k AS line_no, d,
                       (DATE '%s' + (d %% %d)) AS business_date, (10 + d %% 13)::smallint AS local_hour,
                       'I' || ((d * 7 + k) %% 60) AS item_code, 'F' || ((d + k) %% 8) AS family_code,
                       (1 + (d + k) %% 3)::numeric(12, 3) AS quantity,
                       (50 + ((d + k) %% 20) * 10)::numeric(19, 4) AS unit_price,
                       CASE WHEN k = 4 THEN 23 ELSE 16 END::numeric(7, 4) AS tax_rate,
                       md5('emp' || (d %% 8))::uuid AS employee_id,
                       md5('area' || (d %% 3))::uuid AS area_id, 'A' || (d %% 3) AS area_code,
                       (ARRAY['DINE_IN', 'COUNTER', 'TAKEAWAY'])[1 + d %% 3] AS mode
                FROM generate_series(1, %d) AS d, generate_series(1, 4) AS k
                """.formatted(FIRST_DAY, DAYS, DOCUMENTS)).update();
        jdbc.sql("""
                ALTER TABLE bench_line ADD COLUMN gross numeric(19, 4), ADD COLUMN tax numeric(19, 4)
                """).update();
        jdbc.sql("""
                UPDATE bench_line SET gross = quantity * unit_price,
                       tax = round(quantity * unit_price - quantity * unit_price / (1 + tax_rate / 100), 2)
                """).update();
        jdbc.sql("""
                INSERT INTO reporting.sales_record (document_id, store_id, business_date, local_date, local_hour,
                       issued_at, order_id, partition_id, terminal_id, issued_by_employee_id, customer_id, on_account,
                       series_code, number, non_fiscal, currency, total_gross, total_net, total_tax, projected_at)
                SELECT document_id, ?, min(business_date), min(business_date), min(local_hour),
                       min(business_date) + make_interval(hours => min(local_hour)), md5('ord' || min(d))::uuid, NULL,
                       md5('term' || (min(d) % 3))::uuid, md5('emp' || (min(d) % 8))::uuid,
                       CASE WHEN min(d) % 20 = 0 THEN md5('cust' || (min(d) % 50))::uuid END, min(d) % 20 = 0,
                       'FR', min(d), true, 'MZN', sum(gross), sum(gross) - sum(tax), sum(tax), now()
                FROM bench_line GROUP BY document_id
                """).param(store).update();
        jdbc.sql("""
                INSERT INTO reporting.sales_record_line (document_id, line_no, store_id, business_date, local_hour,
                       item_id, item_code, family_code, description, quantity, gross, net, tax, tax_code, tax_rate,
                       served_by_employee_id, area_id, area_code, mode, projected_at)
                SELECT document_id, line_no, ?, business_date, local_hour, md5('item' || item_code)::uuid, item_code,
                       family_code, 'Prato ' || item_code, quantity, gross, gross - tax, tax, 'IVA' || tax_rate::int,
                       tax_rate, employee_id, area_id, area_code, mode, now()
                FROM bench_line
                """).param(store).update();
        jdbc.sql("""
                INSERT INTO reporting.sales_record_tax (document_id, tax_code, tax_rate, store_id, business_date, net,
                       tax, projected_at)
                SELECT document_id, 'IVA' || tax_rate::int, tax_rate, ?, min(business_date), sum(gross) - sum(tax),
                       sum(tax), now()
                FROM bench_line GROUP BY document_id, tax_rate
                """).param(store).update();
        jdbc.sql("""
                INSERT INTO reporting.payment_record (source_type, source_id, store_id, business_date, local_hour,
                       method_code, signed_amount, target_type, order_id, customer_id, employee_id, terminal_id,
                       projected_at)
                SELECT 'PAYMENT', md5('pay' || r.number)::uuid, ?, r.business_date, r.local_hour,
                       (ARRAY['CASH', 'CARD', 'MPESA'])[1 + r.number % 3], r.total_gross, 'ORDER', r.order_id, NULL,
                       r.issued_by_employee_id, r.terminal_id, now()
                FROM reporting.sales_record r WHERE NOT r.on_account
                """).param(store).update();
        jdbc.sql("""
                INSERT INTO reporting.credit_record (charge_id, store_id, business_date, customer_id, order_id, amount,
                       projected_at)
                SELECT md5('charge' || r.number)::uuid, ?, r.business_date, r.customer_id, r.order_id, r.total_gross,
                       now()
                FROM reporting.sales_record r WHERE r.on_account
                """).param(store).update();
        jdbc.sql("""
                INSERT INTO reporting.void_record (void_id, store_id, business_date, local_hour, item_code, description,
                       quantity, gross, reason, wasted, after_dispatch, employee_id, approver_id, projected_at)
                SELECT md5('void' || d || '-' || line_no)::uuid, ?, business_date, local_hour, item_code,
                       'Prato ' || item_code, 1, unit_price, (ARRAY['Cliente desistiu', 'Erro', 'Demora'])[1 + d % 3],
                       d % 2 = 0, d % 3 = 0, employee_id, md5('mgr')::uuid, now()
                FROM bench_line WHERE (d * 4 + line_no) % 50 = 0
                """).param(store).update();
        jdbc.sql("DROP TABLE bench_line").update();
        jdbc.sql("ANALYZE").update();
    }

    private void report(Duration seeded, Map<String, List<Long>> latencies) {
        StringBuilder out = new StringBuilder("\nR3 report benchmark (D86): one store-year, ").append(SAMPLES)
                .append(" samples per report after ").append(WARMUP).append(" warm-up requests\n");
        out.append("  data: ");
        for (String table : List.of("sales_record", "sales_record_line", "sales_record_tax", "payment_record",
                "credit_record", "void_record")) {
            out.append(table).append('=').append(jdbc.sql("SELECT count(*) FROM reporting." + table)
                    .query(Long.class).single()).append(' ');
        }
        out.append("over ").append(DAYS).append(" business days; seeded in ").append(seeded.toSeconds()).append(" s\n");
        out.append("  hardware: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(' ').append(System.getProperty("os.arch"))
                .append(", ").append(Runtime.getRuntime().availableProcessors()).append(" CPUs, JVM max heap ")
                .append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append(" MiB, Java ")
                .append(System.getProperty("java.version"))
                .append("; PostgreSQL in a local Docker container; client and server on the same machine\n");
        out.append(String.format("  %-48s %8s %8s %8s %8s  %s%n", "request", "p50 ms", "p95 ms", "p99 ms", "max ms",
                "target"));
        int misses = 0;
        for (Map.Entry<String, List<Long>> entry : latencies.entrySet()) {
            List<Long> sorted = entry.getValue().stream().sorted().toList();
            long target = entry.getKey().endsWith("(31 d)") ? TARGET_31_DAYS_MS : TARGET_1_DAY_MS;
            double p95 = percentile(sorted, 95);
            boolean miss = p95 >= target;
            misses += miss ? 1 : 0;
            out.append(String.format("  %-48s %8.1f %8.1f %8.1f %8.1f  p95 < %d ms: %s%n", entry.getKey(),
                    percentile(sorted, 50), p95, percentile(sorted, 99), sorted.getLast() / 1e6, target,
                    miss ? "MISS" : "ok"));
        }
        out.append(misses == 0 ? "  every report meets the D86 target: no rollup tables needed\n"
                : "  " + misses + " report(s) MISS the D86 target: open the rollup follow-up slice\n");
        System.out.println(out);
    }

    private static double percentile(List<Long> sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))) / 1e6;
    }
}
