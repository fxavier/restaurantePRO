package com.xavier.backend.promotions.loyalty;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.organization.MutableClock;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots promotions with what loyalty calls: customers (members are customers, P38) and organization (company
 * currency, stores for POS sessions), plus catalog for the rest of promotions, and shared, access and audit, on the
 * shared Testcontainers PostgreSQL. One annotation, one cached context; never assume empty tables.
 *
 * {@code config} is included for the store-scope hook and advisor (ADR 0039): an {@code @ApplicationModuleTest} drops
 * the configuration classes of modules it does not include, also when imported, so the pool would have no scope.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(extraIncludes = {"shared", "access", "audit", "organization", "customers", "catalog", "config"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClock.Config.class, CustomersFixtures.class, LoyaltyFixtures.class})
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN"
})
public @interface LoyaltyModuleTest {
}
