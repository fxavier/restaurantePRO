package com.xavier.backend.identity.oidc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Only when {@code restaurantpro.security.oidc.enabled=true} (D144: off by default, then no OIDC bean, filter or
 * route exists). An empty value (an unset Compose variable) is not {@code true}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = OidcProperties.PREFIX + ".enabled", havingValue = "true")
public @interface ConditionalOnOidcEnabled {
}
