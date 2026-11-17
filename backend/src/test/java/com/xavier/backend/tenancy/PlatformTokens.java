package com.xavier.backend.tenancy;

import com.xavier.backend.config.security.TestJwtFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The bearer tokens of the platform suites, signed by {@link TestJwtFactory} and checked by the real chain (S72's
 * {@code TenantResolutionFilter}, route family PLATFORM): only the ops realm with the realm role
 * {@code platform-operator} passes.
 */
final class PlatformTokens {

    static final String OPERATOR = "operator-" + UUID.randomUUID();

    private PlatformTokens() {
    }

    static RequestPostProcessor operator() {
        return TestJwtFactory.support(OPERATOR, Instant.now()).claim("realm_access", roles("platform-operator"))
                .bearer();
    }

    static RequestPostProcessor opsWithoutRole() {
        return TestJwtFactory.support("support-" + UUID.randomUUID(), Instant.now())
                .claim("realm_access", roles("support")).bearer();
    }

    /** A tenant-realm user who even holds a role named {@code platform-operator}: the realm decides. */
    static RequestPostProcessor tenantUser() {
        return TestJwtFactory.account("user-" + UUID.randomUUID()).claim("realm_access", roles("platform-operator"))
                .bearer();
    }

    private static Map<String, Object> roles(String role) {
        return Map.of("roles", List.of(role));
    }
}
