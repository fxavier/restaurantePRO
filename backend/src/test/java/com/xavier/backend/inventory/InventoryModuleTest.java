package com.xavier.backend.inventory;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.orders.OrdersApi;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Boots the inventory module with what it calls: organization (stores, their zone and cut-off, the company currency),
 * plus shared, access and audit, on the shared Testcontainers PostgreSQL. The clock is a {@link MutableClock}. One
 * annotation, one cached context; never assume empty tables. The facades inventory reads (orders' rounds and voids,
 * catalog's items, S47) are mocks: a test stubs the facts it consumes. {@code config} is included for its store-scope
 * hook (ADR 0039): a module test without it gets a plain pool, and every protected table answers {@code RS001}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(extraIncludes = {"shared", "access", "audit", "organization", "config"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClock.Config.class, InventoryFixtures.class})
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN"
})
@MockitoBean(types = {OrdersApi.class, CatalogApi.class})
public @interface InventoryModuleTest {
}
