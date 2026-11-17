package com.xavier.backend.config.security;

/** The Keycloak realms the backend trusts (D175, P71; O1 resolution 2026-11-16). */
enum Realm {
    /** {@code restaurantpro}: every tenant's users. */
    TENANT,
    /** {@code restaurantpro-ops}: platform operators and support staff, never tenant users. */
    OPS,
    /** {@code restaurantpro-devices}: POS terminals ({@code pos-*}) and print bridges ({@code bridge-*}) only. */
    DEVICES
}
