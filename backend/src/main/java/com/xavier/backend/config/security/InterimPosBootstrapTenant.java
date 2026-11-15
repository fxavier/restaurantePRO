package com.xavier.backend.config.security;

import com.xavier.backend.shared.tenancy.BootstrapTenant;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantScopes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * <b>Interim, S72 → S73 (recorded deviation).</b> A POS request has no bearer token yet: the terminal is still the
 * device token of R2 and the employee the POS session ({@code X-Pos-Session}). Until S73 moves POS to the terminal's
 * Keycloak client and its {@code TERMINAL} membership, every POS route (and only those, {@link RouteFamily#POS})
 * runs in the bootstrap tenant, the only tenant that has terminals before onboarding creates others. It is a constant,
 * never a value of the request (security rule 1), and it is applied by the POS chain alone: no BackOffice, auth,
 * bridge or platform route ever gets it ({@code InterimPosBootstrapTenantTest}). S73 deletes this class.
 */
final class InterimPosBootstrapTenant extends OncePerRequestFilter {

    static final String ORIGIN = "interim:pos";

    private final TenantScopes tenants;

    InterimPosBootstrapTenant(TenantScopes tenants) {
        this.tenants = tenants;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!RouteFamily.isPos(request)) {
            throw new IllegalStateException("The interim POS tenant applies to POS routes only");
        }
        MDC.put(TenantResolutionFilter.MDC_TENANT, BootstrapTenant.ID.toString());
        try {
            tenants.callIn(context(), () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (ServletException e) {
                    throw new IllegalStateException(e);
                }
                return null;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof ServletException servlet) {
                throw servlet;
            }
            throw e;
        } finally {
            MDC.remove(TenantResolutionFilter.MDC_TENANT);
        }
    }

    static TenantContext context() {
        return new TenantContext(BootstrapTenant.ID, MembershipKind.SYSTEM, null, ORIGIN);
    }
}
