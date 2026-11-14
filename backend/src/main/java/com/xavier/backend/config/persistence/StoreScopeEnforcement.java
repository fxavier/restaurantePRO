package com.xavier.backend.config.persistence;

import org.springframework.core.env.Environment;

/**
 * The emergency switch of D170, {@code restaurantpro.store-scope.enforcement} (env
 * {@code RESTAURANTPRO_STORE_SCOPE_ENFORCEMENT}). {@link #PERMISSIVE} makes every checkout all stores, so row-level
 * security stops filtering without any DDL, until a restart with {@link #ENFORCE}; it is logged, alerted and audited at
 * startup. {@code AccessPolicy} is untouched either way.
 */
public enum StoreScopeEnforcement {
    ENFORCE,
    PERMISSIVE;

    public static final String PROPERTY = "restaurantpro.store-scope.enforcement";

    public static StoreScopeEnforcement of(Environment environment) {
        return environment.getProperty(PROPERTY, StoreScopeEnforcement.class, ENFORCE);
    }
}
