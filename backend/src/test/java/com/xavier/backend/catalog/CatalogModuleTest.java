package com.xavier.backend.catalog;

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
 * Boots the catalog module with its real dependencies (shared, access, audit, organization) on the shared
 * Testcontainers PostgreSQL. Organization needs bootstrap settings so the company (and its currency) exists;
 * stores are created through its HTTP API. One annotation keeps every catalog test on one cached context.
 * The database is shared by the whole test JVM: never assume empty tables. {@code config} is included for the
 * store-scope hook and advisor (ADR 0039): an {@code @ApplicationModuleTest} drops the configuration classes of modules
 * it does not include, also when imported, so without it the pool would run with no store scope (RS001).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(extraIncludes = {"shared", "access", "audit", "organization", "config"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN"
})
public @interface CatalogModuleTest {
}
