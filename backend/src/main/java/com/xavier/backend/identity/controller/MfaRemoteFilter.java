package com.xavier.backend.identity.controller;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.identity.exception.MfaRefusedException;
import com.xavier.backend.identity.service.MfaPolicy;
import com.xavier.backend.identity.service.MfaSessionAttributes;
import com.xavier.backend.identity.service.RemoteAddresses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * D140 "per request, not only at login": while MFA is not {@code OFF}, a BackOffice session that never proved a
 * second factor (a LAN login in {@code SENSITIVE}) serves no remote request: 403 {@code MFA_REQUIRED}. A cookie taken
 * from the LAN is then useless from outside; a step-up ({@code /api/v1/auth/**} stays open) satisfies it. POS
 * (EMPLOYEE) sessions are never affected. Registered after the security filter chain, like
 * {@link MustChangeCredentialFilter}.
 */
@Component
class MfaRemoteFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MfaRemoteFilter.class);

    private final MfaPolicy policy;
    private final RemoteAddresses addresses;
    private final JsonMapper json;

    MfaRemoteFilter(MfaPolicy policy, RemoteAddresses addresses, JsonMapper json) {
        this.policy = policy;
        this.addresses = addresses;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (refused(request)) {
            MfaRefusedException refusal = MfaRefusedException.required();
            response.setStatus(refusal.getStatusCode().value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(response.getOutputStream(), refusal.getBody());
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean refused(HttpServletRequest request) {
        if (!policy.enforced() || isAuthRoute(request)) {
            return false;
        }
        HttpSession session = request.getSession(false);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (session == null || authentication == null || !(authentication.getPrincipal() instanceof Actor actor)
                || actor.kind() != ActorKind.ACCOUNT || MfaSessionAttributes.secondFactorAt(session) != null
                || !addresses.isRemote(request)) {
            return false;
        }
        log.warn("Remote request refused: session of user {} never proved a second factor: {} {}", actor.userId(),
                request.getMethod(), request.getRequestURI());
        return true;
    }

    private static boolean isAuthRoute(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/v1/auth/");
    }
}
