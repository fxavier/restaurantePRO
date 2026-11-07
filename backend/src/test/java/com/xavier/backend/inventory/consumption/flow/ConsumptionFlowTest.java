package com.xavier.backend.inventory.consumption.flow;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.dining.DiningFixtures;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.consumption.service.RoundConsumption;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.settlement.FakePaymentProvider;
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
 * Inventory with the real modules whose facts it consumes (orders, through dining and catalog) and what a sale needs
 * to be paid and closed (cash, fiscal, identity, settlement, customers), on the shared Testcontainers PostgreSQL:
 * rounds, transfers, voids and closes are real, made through the POS APIs (settlement's fixtures). Spy:
 * {@link RoundConsumption}, so a test can hold or crash a consumption. The clock is {@link SettlementTestClock}.
 * Never assume empty tables; every test uses fresh stores.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(extraIncludes = {"shared", "access", "audit", "organization", "dining", "catalog", "orders",
        "cash", "fiscal", "identity", "settlement", "customers"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DiningFixtures.class, OrdersFixtures.class, CashFixtures.class,
        FiscalFixtures.class, SettlementFixtures.class, SettlementTestClock.Config.class,
        FakePaymentProvider.Config.class, InventoryFixtures.class})
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN",
        "restaurantpro.bootstrap.admin.username=inventory-bootstrap-admin",
        "restaurantpro.bootstrap.admin.initial-password-file=src/test/resources/identity/admin-initial-password.txt",
        "restaurantpro.settlement.provider-timeout=PT2S",
        "restaurantpro.settlement.sweep-interval=PT1H"
})
@MockitoSpyBean(types = RoundConsumption.class)
@MockitoBean(types = ApproverVerifier.class)
public @interface ConsumptionFlowTest {
}
