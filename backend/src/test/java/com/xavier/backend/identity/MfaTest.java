package com.xavier.backend.identity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The identity test application with second factors usable: a fixed test key file (never a real key), the store LAN
 * {@code 192.168.1.0/24} plus loopback (MockMvc's default address stays local, a test sets {@code 203.0.113.5} to be
 * remote), a {@link MutableClock} and a {@link SwitchableMfaPolicy}. One annotation, one cached context for every MFA
 * test.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@IdentityTest
@Import({MutableClock.Config.class, SwitchableMfaPolicy.Config.class})
@TestPropertySource(properties = {
        "restaurantpro.security.mfa.key-file=src/test/resources/identity/mfa-test.key",
        "restaurantpro.security.lan-cidrs=127.0.0.0/8,::1/128,192.168.1.0/24",
        "restaurantpro.identity.support-expiry-cron=-"
})
public @interface MfaTest {

    String LAN_ADDRESS = "192.168.1.10";
    String REMOTE_ADDRESS = "203.0.113.5";
}
