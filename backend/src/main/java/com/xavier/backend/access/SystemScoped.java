package com.xavier.backend.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the annotated method in a named system scope (D166), applied by {@code config.persistence.StoreScopeAdvisor}
 * before any transaction of the method starts. Required on every {@code @Scheduled} method, {@code ApplicationRunner}
 * and {@code ApplicationReadyEvent} listener, and on a module listener whose event carries no {@code storeId}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SystemScoped {

    SystemScope value();
}
