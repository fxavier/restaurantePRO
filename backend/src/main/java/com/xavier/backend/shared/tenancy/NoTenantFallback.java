package com.xavier.backend.shared.tenancy;

import java.util.Optional;
import org.springframework.stereotype.Component;

/** Production: an unbound thread has no tenant (fail closed). */
@Component
class NoTenantFallback implements TenantFallback {

    @Override
    public Optional<TenantContext> whenUnbound() {
        return Optional.empty();
    }
}
