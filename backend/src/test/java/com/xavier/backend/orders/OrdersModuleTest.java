package com.xavier.backend.orders;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.dining.DiningChangeStream;
import com.xavier.backend.dining.DiningFixtures;
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
 * Boots orders with the modules it calls: organization (stores, business day, {@code StoreActivity}), dining (table
 * occupations), catalog (the frozen catalog), plus shared, access and audit, on the shared Testcontainers PostgreSQL.
 * Stores, catalogs and tables are built through those modules' HTTP APIs ({@link OrdersFixtures}). One annotation,
 * one cached context. The database is shared by the whole test JVM: never assume empty tables.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(extraIncludes = {"shared", "access", "audit", "organization", "dining", "catalog"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DiningFixtures.class, OrdersFixtures.class, OrderEventProbe.class,
        DiningChangeStream.Probe.class})
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN"
})
public @interface OrdersModuleTest {
}
