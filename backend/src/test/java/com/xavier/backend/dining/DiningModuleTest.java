package com.xavier.backend.dining;

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
 * Boots the dining module with the modules it calls (organization for store existence, plus shared, access,
 * audit) on the shared Testcontainers PostgreSQL. Organization needs the company bootstrap settings to start.
 * Every dining test uses this one annotation, so they share one cached context (and one connection pool).
 * The database is shared by the whole test JVM: never assume empty tables.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(extraIncludes = {"shared", "access", "audit", "organization"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DiningFixtures.class})
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN",
        // One PostgreSQL serves every cached context of the test JVM (max_connections 100, 10 per default
        // pool); a small pool keeps this context from exhausting it. Enough for the two-request race tests.
        "spring.datasource.hikari.maximum-pool-size=4"
})
public @interface DiningModuleTest {
}
