package com.xavier.backend.shared.tenancy;

import java.util.UUID;

/**
 * The bootstrap tenant (D180, P70 = none): the company of an installation that existed before R6, and the tenant every
 * existing test runs in (D189). The same literal is in the migrations (V1010, V2005, V15001).
 */
public final class BootstrapTenant {

    public static final UUID ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private BootstrapTenant() {
    }
}
