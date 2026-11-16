package com.xavier.backend.config.security;

import com.xavier.backend.access.ActorAuthentication;
import com.xavier.backend.access.ActorDirectory;
import com.xavier.backend.access.Assurance;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantScopes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * <b>Interim, S72 → S73.</b> The employee of a POS request: the opaque POS session token of {@code X-Pos-Session}
 * (D182's token, issued by the PIN login and stored only as its SHA-256), resolved to an {@code EMPLOYEE} actor on its
 * terminal and store, with no second factor ({@link Assurance#NONE}: a PIN session never steps up). A missing,
 * unknown or expired token leaves the request unauthenticated, and the POS chain answers 401
 * {@code POS_SESSION_INVALID}. S73 also requires the terminal's bearer token and binds the session to it.
 */
final class PosSessionFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Pos-Session";

    private final ActorDirectory actors;
    private final TenantScopes tenants;

    PosSessionFilter(ActorDirectory actors, TenantScopes tenants) {
        this.actors = actors;
        this.tenants = tenants;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token != null && !token.isBlank()) {
            actors.posSession(token.strip()).ifPresent(actor -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                // the tenant the POS chain bound (InterimPosBootstrapTenant, until S73's TERMINAL membership)
                context.setAuthentication(new ActorAuthentication(actor, new TenantContext(tenants.requireTenant(),
                        MembershipKind.TERMINAL, actor.terminalId(), InterimPosBootstrapTenant.ORIGIN),
                        Assurance.NONE));
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }
}
