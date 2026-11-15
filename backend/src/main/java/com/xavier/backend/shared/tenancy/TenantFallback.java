package com.xavier.backend.shared.tenancy;

import java.util.Optional;

/**
 * The tenant of a thread with no explicit binding, no resolved request tenant and no tenant-bound actor. Production
 * answers none, so a protected table raises {@code RS003} (fail closed); the test harness answers the bootstrap tenant
 * on the marked JUnit thread only (D189 as amended, the D171 mechanics).
 */
public interface TenantFallback {

    Optional<TenantContext> whenUnbound();
}
