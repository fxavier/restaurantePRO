package com.xavier.backend.cash;

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
 * Boots the cash module with the modules it calls: organization (stores, business-day cutoff, {@code StoreActivity}),
 * identity (approver checks for the day reopen), plus shared, access and audit, on the shared Testcontainers
 * PostgreSQL. The clock is {@link CashTestClock}, which tests may set. One annotation, one cached context.
 * The database is shared by the whole test JVM: never assume empty tables.
 * {@code config} brings the store-scope hook and advisor (ADR 0039, {@code StoreScopeConfiguration}): a module test
 * drops the definitions of a module it does not include, even an imported one, and without the hook no connection
 * carries a store scope, so every table with a policy raises {@code RS001}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(extraIncludes = {"shared", "access", "audit", "organization", "identity", "config"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, CashTestClock.Config.class, CashFixtures.class})
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN",
        "restaurantpro.bootstrap.admin.username=cash-bootstrap-admin",
        "restaurantpro.bootstrap.admin.initial-password-file=src/test/resources/identity/admin-initial-password.txt"
})
public @interface CashModuleTest {
}
