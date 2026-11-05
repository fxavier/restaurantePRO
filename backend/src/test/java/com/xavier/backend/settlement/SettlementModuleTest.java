package com.xavier.backend.settlement;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.cash.CashApi;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.dining.DiningFixtures;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapter;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.settlement.service.OrderSettlementSteps;
import com.xavier.backend.settlement.service.PaymentSubmitter;
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
 * Boots settlement with every module it coordinates: orders (and what orders needs: organization, dining, catalog),
 * cash, fiscal and identity, plus shared, access and audit, on the shared Testcontainers PostgreSQL. One annotation,
 * one cached context. The clock is {@link SettlementTestClock}; the integrated provider is
 * {@link FakePaymentProvider} (code {@code FAKE}). Spies: the fiscal adapter and the order steps, so a test can make a
 * step fail after it committed (D63); the orders, cash and fiscal facades, so a test can prove what was not read (D42);
 * {@link ApproverVerifier} is a mock (identity proves the PIN check itself).
 * The database is shared by the whole test JVM: never assume empty tables.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(extraIncludes = {"shared", "access", "audit", "organization", "dining", "catalog", "orders",
        "cash", "fiscal", "identity"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DiningFixtures.class, OrdersFixtures.class, CashFixtures.class,
        FiscalFixtures.class, SettlementFixtures.class, SettlementTestClock.Config.class,
        FakePaymentProvider.Config.class})
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN",
        "restaurantpro.bootstrap.admin.username=settlement-bootstrap-admin",
        "restaurantpro.bootstrap.admin.initial-password-file=src/test/resources/identity/admin-initial-password.txt",
        "restaurantpro.settlement.provider-timeout=PT2S",
        // the tests drive the sweeper by hand (AttemptSweeper.sweep), with the test clock
        "restaurantpro.settlement.sweep-interval=PT1H"
})
@MockitoSpyBean(types = {InternalFiscalAdapter.class, OrderSettlementSteps.class, PaymentSubmitter.class,
        OrdersApi.class, CashApi.class, FiscalApi.class})
@MockitoBean(types = ApproverVerifier.class)
public @interface SettlementModuleTest {
}
