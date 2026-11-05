package com.xavier.backend.identity.controller;

import com.xavier.backend.identity.service.SupportGrantService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * A support session (D83) serves a request only while its grant holds: before the grant's end, read from the
 * session, and still ACTIVE (one primary-key read). Otherwise the session is invalidated and the request gets the
 * security chain's 401. Ending a grant also deletes its sessions, but deletion alone can miss one: a revoke that
 * commits while a login is still saving its session, or a session in use when the grant ends before the job runs.
 *
 * <p>On the login routes a dead support session is dropped and the request goes on, so the user can log in again.
 * Registered after the security filter chain and before {@link MustChangeCredentialFilter}.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
class SupportSessionFilter extends OncePerRequestFilter {

    private static final Set<String> LOGIN_ROUTES = Set.of("/api/v1/auth/login", "/api/v1/auth/pos-login");

    private final SupportGrantService grants;
    private final JsonMapper json;

    SupportSessionFilter(SupportGrantService grants, JsonMapper json) {
        this.grants = grants;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(LoginSessions.SUPPORT_VALID_UNTIL) instanceof Instant validUntil)
                || grants.sessionGrantHolds((UUID) session.getAttribute(LoginSessions.SUPPORT_GRANT_ID), validUntil)) {
            chain.doFilter(request, response);
            return;
        }
        session.invalidate();
        if (LOGIN_ROUTES.contains(request.getRequestURI().substring(request.getContextPath().length()))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(),
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication is required."));
    }
}
