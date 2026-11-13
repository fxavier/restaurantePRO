package com.xavier.backend.config.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * §12, D149: "Não usar order_id em labels de métricas". The application's own meters are labelled only by store,
 * status, state, module, kind, outcome or name; no meter (ours or the framework's) carries an id-shaped label value
 * except a {@code store}; and after an order, a payment, a print job and a fiscal document exist, none of their ids
 * appears in the Prometheus scrape.
 */
@AlertsIntegrationTest
class MetricLabelsTest {

    static final Set<String> APPLICATION_KEYS = Set.of("store", "status", "state", "module", "kind", "outcome",
            "name");
    /** Meters of Spring Boot, Micrometer, Hikari, Tomcat, the JVM and Spring Modulith, with their own tag keys. */
    static final List<String> FRAMEWORK_PREFIXES = List.of("jvm.", "process.", "system.", "hikaricp.", "jdbc.",
            "tomcat.", "http.", "spring.", "application.", "executor", "logback.", "disk.", "tasks.", "modulith.",
            "jakarta.", "hibernate.", "cache.", "jetty.", "security.", "server.");
    static final Pattern UUID_SHAPED = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Autowired
    MeterRegistry meters;

    @Autowired
    SettlementFixtures settlement;

    @Autowired
    ProductionFixtures production;

    @Autowired
    JdbcClient jdbc;

    @LocalManagementPort
    int managementPort;

    @Test
    void noMeterIsLabelledWithAnIdOfAnOrderPaymentJobOrDocument() throws Exception {
        Sale sale = settlement.sale("2");
        production.barPrinter(sale.store(), "127.0.0.1", closedPort());
        settlement.confirmRound(sale.shop(), sale.cashier(), sale.order(), "1",
                settlement.orderVersion(sale.cashier(), sale.order()));
        UUID job = awaitPrintJob(sale.store());
        UUID payment = SettlementFixtures.id(settlement.payCash(sale, "37.50", "37.50")
                .andExpect(status().isCreated()));
        UUID document = SettlementFixtures.id(settlement.issue(sale.cashier(), sale.order(),
                UUID.randomUUID().toString()).andExpect(status().isCreated()));

        assertThat(violations(meters)).isEmpty();
        String scrape = scrape();
        assertThat(scrape).contains("event_publications_oldest_incomplete_seconds");
        for (UUID id : List.of(sale.order(), payment, job, document)) {
            assertThat(scrape).doesNotContain(id.toString());
        }
    }

    @Test
    void theRuleFiresOnAnIdLabelOrAnUnknownKey() {
        SimpleMeterRegistry rogue = new SimpleMeterRegistry();
        rogue.counter("orders_confirmed", "order", UUID.randomUUID().toString()).increment();
        rogue.counter("print_retries", "status", UUID.randomUUID().toString()).increment();
        rogue.counter("jvm.custom", "id", UUID.randomUUID().toString()).increment();
        rogue.counter("payments", "store", UUID.randomUUID().toString()).increment();
        rogue.counter("disk.free", "path", "/builds/" + UUID.randomUUID() + "/backend/.").increment();

        assertThat(violations(rogue)).hasSize(4).anyMatch(v -> v.contains("orders_confirmed") && v.contains("order"))
                .anyMatch(v -> v.contains("print_retries")).anyMatch(v -> v.contains("jvm.custom"))
                .noneMatch(v -> v.startsWith("payments"))
                .as("a checkout path is not an id").noneMatch(v -> v.startsWith("disk.free"));
    }

    /**
     * Every application meter's tag keys are among {@link #APPLICATION_KEYS}; only a {@code store} tag may hold an
     * id-shaped value, on any meter (Spring's {@code disk.*} {@code path} is a directory, which may hold a UUID).
     */
    static List<String> violations(MeterRegistry registry) {
        List<String> violations = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            boolean framework = FRAMEWORK_PREFIXES.stream().anyMatch(name::startsWith);
            for (Tag tag : meter.getId().getTags()) {
                if (!framework && !APPLICATION_KEYS.contains(tag.getKey())) {
                    violations.add(name + ": key " + tag.getKey() + " is not an allowed label");
                }
                boolean checkoutPath = name.startsWith("disk.") && tag.getKey().equals("path");
                if (!tag.getKey().equals("store") && !checkoutPath && UUID_SHAPED.matcher(tag.getValue()).find()) {
                    violations.add(name + ": " + tag.getKey() + " holds an id");
                }
            }
        }
        return violations;
    }

    private String scrape() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + managementPort + "/actuator/prometheus"))
                .header("Authorization", "Bearer " + AlertsIntegrationTest.TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private UUID awaitPrintJob(UUID store) throws InterruptedException {
        ProductionFixtures.await(() -> !jobsOf(store).isEmpty(), "a print job in store " + store);
        return jobsOf(store).getFirst();
    }

    private List<UUID> jobsOf(UUID store) {
        return jdbc.sql("SELECT id FROM production.print_job WHERE store_id = ?").param(store).query(UUID.class)
                .list();
    }

    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
