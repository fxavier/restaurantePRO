package com.xavier.backend.organization;

import com.xavier.backend.TestcontainersConfiguration;
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
 * Boots the organization module (+ shared, access, audit) on the shared Testcontainers PostgreSQL, with
 * bootstrap settings so the company exists. One annotation keeps every organization test on one cached
 * context. The database is shared by the whole test JVM: never assume empty tables.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(extraIncludes = {"shared", "access", "audit"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClock.Config.class})
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN"
})
public @interface OrganizationModuleTest {
}
