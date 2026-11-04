package com.xavier.backend.fiscal;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapter;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Boots the fiscal module with the modules it calls (organization for the company and store snapshots and the
 * StoreActivity that requirePos needs; shared, access, audit) on the shared Testcontainers PostgreSQL. Every fiscal
 * test uses this one annotation so they share one cached context. Never assume empty tables.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(extraIncludes = {"shared", "access", "audit", "organization"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FiscalFixtures.class})
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN"
})
@MockitoSpyBean(types = InternalFiscalAdapter.class) // GaplessNumberingTest forces an adapter failure
public @interface FiscalModuleTest {
}
