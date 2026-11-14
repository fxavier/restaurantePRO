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
 * Boots the dining module with the modules it calls (organization for store existence, customers for a
 * reservation's customer, plus shared, access, audit) on the shared Testcontainers PostgreSQL. Organization needs the company bootstrap settings to start.
 * Every dining test uses this one annotation, so they share one cached context (and one connection pool).
 * The database is shared by the whole test JVM: never assume empty tables. {@code config} is included for its
 * store-scope plumbing (ADR 0039, D165, D166): a module test drops the bean definitions of modules it does not boot,
 * so without it the pool would have no store-scope hook and every protected table would answer {@code RS001}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(extraIncludes = {"config", "shared", "access", "audit", "organization", "customers"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DiningFixtures.class, DiningChangeStream.Probe.class})
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN"
})
public @interface DiningModuleTest {
}
