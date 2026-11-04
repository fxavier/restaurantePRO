package com.xavier.backend.identity;

import com.xavier.backend.TestcontainersConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The whole application (real security chain, Spring Session JDBC, organization) on the shared Testcontainers
 * PostgreSQL, with the company bootstrapped and admin bootstrap settings. Login is about the real filter chain, so identity tests boot it
 * all; one annotation keeps them on one cached context. Never assume empty tables.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN",
        "restaurantpro.bootstrap.admin.username=" + IdentityTest.ADMIN_USERNAME,
        "restaurantpro.bootstrap.admin.initial-password-file=src/test/resources/identity/admin-initial-password.txt"
})
public @interface IdentityTest {

    /** The bootstrap admin of identity test contexts; its initial password is in the file above (test fixture). */
    String ADMIN_USERNAME = "bootstrap-admin";
    String ADMIN_INITIAL_PASSWORD = "bootstrap admin passphrase";
}
