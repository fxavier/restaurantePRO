package com.xavier.backend.config.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link SecurityHttpTest} with the devices realm configured and its admin port faked ({@link FakeDeviceProvider}):
 * the suites of terminal enrolment, POS sessions on terminal tokens and the print bridge share this one context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SecurityHttpTest
@Import(FakeDeviceProvider.class)
@TestPropertySource(properties = "restaurantpro.security.issuers.devices=" + TestJwtFactory.DEVICES_ISSUER)
public @interface DeviceHttpTest {
}
