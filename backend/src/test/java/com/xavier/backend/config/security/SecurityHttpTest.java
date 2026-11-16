package com.xavier.backend.config.security;

import com.xavier.backend.TestcontainersConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The whole application with its real security chains, on the shared PostgreSQL, trusting {@link TestJwtFactory}'s
 * keys for both realms (D189), with the issuers configured as in production. One annotation keeps the security suites
 * on one cached context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(Members.CloseTenantsAfterClass.class)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN",
        "restaurantpro.security.issuers.tenant=" + TestJwtFactory.TENANT_ISSUER,
        "restaurantpro.security.issuers.ops=" + TestJwtFactory.OPS_ISSUER
})
public @interface SecurityHttpTest {
}
