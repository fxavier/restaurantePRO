package com.xavier.backend.production;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.dining.DiningFixtures;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.orders.OrdersCatalog;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersProductionApi;
import com.xavier.backend.shared.notify.ChangeNotifier;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Boots production with the modules whose events and API it uses for real: orders (rounds, through dining, catalog,
 * organization and identity, whose ApproverVerifier orders' voids need) and fiscal (documents and their copies),
 * with the R4 fixtures for delivery customers and menus, plus shared, access and audit, on the shared
 * Testcontainers PostgreSQL. {@code config} is not included, so nothing is scheduled here: tests run the worker
 * with {@code PrintWorker.poll()}. Full-application contexts of other tests (cached, same database) do run it, so
 * tests wait for states and match printed content instead of assuming they are alone. One annotation, one cached
 * context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(extraIncludes = {"shared", "access", "audit", "organization", "dining", "catalog", "orders",
        "fiscal", "identity", "customers", "promotions"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DiningFixtures.class, OrdersFixtures.class, FiscalFixtures.class,
        ProductionFixtures.class, CustomersFixtures.class, OrdersCatalog.class, DrawerKicks.class})
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN",
        // identity: orders' void use case needs ApproverVerifier; identity bootstraps an admin at start
        "restaurantpro.bootstrap.admin.username=production-bootstrap-admin",
        "restaurantpro.bootstrap.admin.initial-password-file=src/test/resources/identity/admin-initial-password.txt",
        "restaurantpro.production.printer.write-timeout=PT1S"
})
// BatchSurvivesCrashTest crashes one listener run; PrintQueueTest checks the PRINT_JOBS notices
@MockitoSpyBean(types = {OrdersProductionApi.class, ChangeNotifier.class})
public @interface ProductionModuleTest {
}
