package com.xavier.backend.identity.oidc;

import com.xavier.backend.identity.MfaTest;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * The MFA test application ({@link MfaTest}) with OIDC federation enabled against one shared, running
 * {@link StubIdp}. One annotation, one cached context for every federated-login test.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@MfaTest
@ContextConfiguration(initializers = OidcTest.Enabled.class)
public @interface OidcTest {

    /** Started once per JVM; the backend discovers it on the first OIDC request. */
    StubIdp IDP = StubIdp.start();

    class Enabled implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "restaurantpro.security.oidc.enabled=true",
                    "restaurantpro.security.oidc.issuer-uri=" + IDP.issuer(),
                    "restaurantpro.security.oidc.client-id=" + StubIdp.CLIENT_ID,
                    "restaurantpro.security.oidc.client-secret-file=src/test/resources/identity/oidc-client-secret.txt",
                    "restaurantpro.security.oidc.display-name=Stub IdP"
            ).applyTo(context);
        }
    }
}
