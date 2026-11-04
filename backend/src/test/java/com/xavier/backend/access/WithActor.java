package com.xavier.backend.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.test.context.support.WithSecurityContext;

/**
 * Runs a test as an authenticated {@link Actor}, without S2's login (PRD S0).
 *
 * <pre>{@code
 * @WithActor(permissions = Permission.DINING_MANAGE, storeId = "0190a1b2-...")   // only in that store
 * @WithActor(permissions = {Permission.STORE_MANAGE, Permission.CATALOG_MANAGE}) // installation-wide
 * }</pre>
 *
 * Works on a test class or method of any Spring test ({@code @SpringBootTest}, {@code @WebMvcTest},
 * {@code @ApplicationModuleTest}); with MockMvc the actor is the request's authenticated user.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@WithSecurityContext(factory = WithActorSecurityContextFactory.class)
public @interface WithActor {

    /** Fixed default so assertions can name the actor. */
    String DEFAULT_USER_ID = "00000000-0000-7000-8000-000000000001";

    Permission[] permissions() default {};

    /** If set, every permission is granted only in this store and {@link Actor#storeId()} is this store. */
    String storeId() default "";

    String userId() default DEFAULT_USER_ID;

    /** Set for a POS actor. */
    String employeeId() default "";

    /** Set for a POS actor. */
    String terminalId() default "";
}
