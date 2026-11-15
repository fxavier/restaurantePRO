package com.xavier.backend.shared.tenancy;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Interim, until S72: every request runs in the bootstrap tenant, the installation's only tenant before onboarding
 * exists (no platform API creates another one until S75), so behaviour is unchanged (spec S71). It is a constant,
 * never a value of the request (security rule 1), and it ranks below a tenant-bound principal and any explicit
 * binding. S72 replaces it with {@code config.security.TenantResolutionFilter}, which resolves the tenant from the
 * JWT's membership (D177). First filter, before the security chain loads the session.
 */
@Component
class BootstrapTenantRequests implements Filter, Ordered {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        TenantScopes.enterRequest();
        try {
            chain.doFilter(request, response);
        } finally {
            TenantScopes.leaveRequest();
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
