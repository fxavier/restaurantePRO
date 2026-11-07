package com.xavier.backend.reporting;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.dining.DiningFixtures;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.orders.OrdersCatalog;
import com.xavier.backend.orders.OrdersPromotions;
import com.xavier.backend.promotions.PromotionsFixtures;
import com.xavier.backend.reporting.service.CostProjections;
import com.xavier.backend.reporting.service.Projections;
import com.xavier.backend.settlement.FakePaymentProvider;
import com.xavier.backend.settlement.SettlementFacts;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementTestClock;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Boots reporting with every module whose facts it projects (and what they need), on the shared Testcontainers
 * PostgreSQL, so a test produces real facts through the POS APIs (settlement's fixtures) and reads the projections.
 * {@code customers} is included because settlement depends on it from S29, {@code inventory} for the cost of goods
 * (S53: real consumption, waste and variances). Spies: {@link SettlementFacts}, so refund and credit-charge facts can
 * be stubbed until S29/S30 record them, and {@link Projections} and {@link CostProjections}, so a test can hold a
 * projection (freshness). The clock is {@link SettlementTestClock}. The database is shared by the whole test JVM:
 * never assume empty tables; every test uses fresh stores.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(extraIncludes = {"shared", "access", "audit", "organization", "dining", "catalog", "orders",
        "cash", "fiscal", "identity", "settlement", "customers", "promotions", "inventory"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DiningFixtures.class, OrdersFixtures.class, CashFixtures.class,
        FiscalFixtures.class, SettlementFixtures.class, SettlementTestClock.Config.class,
        FakePaymentProvider.Config.class, ReportingFixtures.class, InventoryFixtures.class, PromotionsFixtures.class,
        OrdersPromotions.class, OrdersCatalog.class, CostKitchen.class})
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN",
        "restaurantpro.bootstrap.admin.username=reporting-bootstrap-admin",
        "restaurantpro.bootstrap.admin.initial-password-file=src/test/resources/identity/admin-initial-password.txt",
        "restaurantpro.settlement.provider-timeout=PT2S",
        "restaurantpro.settlement.sweep-interval=PT1H"
})
@MockitoSpyBean(types = {SettlementFacts.class, Projections.class, CostProjections.class})
@MockitoBean(types = ApproverVerifier.class)
public @interface ReportingModuleTest {
}
