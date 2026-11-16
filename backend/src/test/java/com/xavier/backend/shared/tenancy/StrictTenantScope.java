package com.xavier.backend.shared.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Turns the bootstrap-tenant fixture fallback off for a test class (D189 as amended): the JUnit thread then has
 * production semantics, so code with no explicit binding and no tenant-bound actor is unbound ({@code RS003} on a
 * tenant table). For the tenant isolation suites and {@code ActorlessTenantScopeTest}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface StrictTenantScope {
}
