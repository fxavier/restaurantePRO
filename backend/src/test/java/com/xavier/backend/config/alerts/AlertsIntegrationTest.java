package com.xavier.backend.config.alerts;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.audit.repository.AuditEventRepository;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.dining.DiningFixtures;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.inventory.repository.InventoryQueries;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.reporting.service.ReportService;
import com.xavier.backend.settlement.FakePaymentProvider;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementTestClock;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.AlertSource;
import com.xavier.backend.shared.ops.OperationalAlert;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * One cached full-application context for the S61 alert, health, scrape and metric-label tests: a real server with
 * the management port, the scrape token file of {@code src/test/resources/config/metrics-token.txt}, the settlement
 * and production fixtures (to seed an order, a payment, a print job and a document), spies on the report,
 * inventory and audit reads (to force an export failure), and three test alert sources: fixed alerts of two stores and of the
 * installation, a source that always fails, and one that counts its calls. The clock is {@link SettlementTestClock}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "restaurantpro.metrics.token-file=src/test/resources/config/metrics-token.txt",
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN",
        "restaurantpro.bootstrap.admin.username=alerts-bootstrap-admin",
        "restaurantpro.bootstrap.admin.initial-password-file=src/test/resources/identity/admin-initial-password.txt",
        "restaurantpro.settlement.provider-timeout=PT2S",
        "restaurantpro.settlement.sweep-interval=PT1H",
        "restaurantpro.settlement.credit-sweep-interval=PT1H",
        "restaurantpro.settlement.in-doubt-poll-interval=PT1H",
        // this context stays cached for the rest of the suite: its event recovery job must not resubmit other
        // contexts' publications from the shared database (it first runs one interval after the start)
        "restaurantpro.events.resubmit-interval=PT1H"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AlertsIntegrationTest.Sources.class, DiningFixtures.class,
        OrdersFixtures.class, CashFixtures.class, FiscalFixtures.class, CustomersFixtures.class,
        SettlementFixtures.class, ProductionFixtures.class, SettlementTestClock.Config.class,
        FakePaymentProvider.Config.class})
@MockitoSpyBean(types = {ReportService.class, InventoryQueries.class, AuditEventRepository.class})
public @interface AlertsIntegrationTest {

    String STORE_A = "0190a000-0000-7000-8000-0000000061aa";
    String STORE_B = "0190b000-0000-7000-8000-0000000061bb";
    String TOKEN = "test-scrape-token-not-a-secret";

    @TestConfiguration(proxyBeanMethods = false)
    class Sources {

        static final Instant SINCE = Instant.parse("2026-11-12T08:00:00Z");

        @Bean
        AlertSource fixedTestAlerts() {
            return new FixedTestAlerts();
        }

        @Bean
        AlertSource failingTestSource() {
            return new FailingTestSource();
        }

        @Bean
        CountingTestSource countingTestSource() {
            return new CountingTestSource();
        }
    }

    /** A WARNING of store A, a CRITICAL of store B and an installation-wide CRITICAL. */
    final class FixedTestAlerts implements AlertSource {

        @Override
        public List<OperationalAlert> alerts() {
            return List.of(
                    new OperationalAlert("TEST_STORE_A", AlertSeverity.WARNING, UUID.fromString(STORE_A), 1,
                            Sources.SINCE, "Store A"),
                    new OperationalAlert("TEST_STORE_B", AlertSeverity.CRITICAL, UUID.fromString(STORE_B), 2,
                            Sources.SINCE, "Store B"),
                    new OperationalAlert("TEST_INSTALLATION", AlertSeverity.CRITICAL, null, 1, Sources.SINCE,
                            "Installation"));
        }
    }

    final class FailingTestSource implements AlertSource {

        @Override
        public List<OperationalAlert> alerts() {
            throw new IllegalStateException("the test source always fails");
        }
    }

    final class CountingTestSource implements AlertSource {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<OperationalAlert> alerts() {
            calls.incrementAndGet();
            return List.of();
        }
    }
}
