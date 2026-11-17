package com.xavier.backend.config.security;

/** The two Keycloak realms the backend trusts (D175, P71). */
enum Realm {
    /** {@code restaurantpro}: every tenant's users (and, from S73, terminals and bridges). */
    TENANT,
    /** {@code restaurantpro-ops}: platform operators and support staff, never tenant users. */
    OPS
}
