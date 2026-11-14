package com.xavier.backend.promotions;

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
 * Boots promotions with the modules it calls: organization (stores, zones, the company currency), customers (loyalty
 * members) and catalog (the
 * published catalog a release validates against), plus shared, access and audit, on the shared Testcontainers
 * PostgreSQL. Stores and catalogs are built through their HTTP APIs ({@link PromotionsFixtures}). One annotation, one
 * cached context. The database is shared by the whole test JVM: never assume empty tables.
 *
 * {@code config} is included for the store-scope hook and advisor (ADR 0039): an {@code @ApplicationModuleTest} drops
 * the configuration classes of modules it does not include, also when imported, so the pool would have no scope.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(extraIncludes = {"shared", "access", "audit", "organization", "customers", "catalog", "config"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PromotionsFixtures.class, PromotionsNoticeTest.NoticeStream.class})
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN"
})
public @interface PromotionsModuleTest {
}
