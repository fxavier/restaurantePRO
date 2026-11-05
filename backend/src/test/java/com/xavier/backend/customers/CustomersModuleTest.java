package com.xavier.backend.customers;

import com.xavier.backend.TestcontainersConfiguration;
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
 * Boots the customers module with what it calls: organization (company currency, stores for POS sessions and
 * {@code StoreActivity}), plus shared, access and audit, on the shared Testcontainers PostgreSQL. The clock is a
 * {@link MutableClock} that only moves forward. One annotation, one cached context; never assume empty tables.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(extraIncludes = {"shared", "access", "audit", "organization"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClock.Config.class, CustomersFixtures.class})
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN"
})
public @interface CustomersModuleTest {
}
