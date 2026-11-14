package com.xavier.backend.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Turns the fixture fallback off for a test class (D171): the JUnit thread then has production semantics too, so
 * code with neither an actor nor an explicit scope is unset ({@code RS001} on a protected table). For the isolation
 * suites and {@code ActorlessStoreScopeTest}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface StrictStoreScope {
}
