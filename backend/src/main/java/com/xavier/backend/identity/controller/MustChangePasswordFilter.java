package com.xavier.backend.identity.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * While a session's account must change its password (D30), only {@code /api/v1/auth/**} is served (change the
 * password, read the session, log out, log in as someone else); everything else gets a 403 that says why.
 *
 * <p>Defence in depth: such a session also holds no permission at all (see {@code LoginService.sessionOf}), so
 * every use case and the actuator rule refuse it anyway; this filter only gives the client a clear answer.
 * Registered by Spring Boot as a servlet filter after the security filter chain, so CSRF and authentication
 * have already been checked when it runs.
 */
@Component
class MustChangePasswordFilter extends OncePerRequestFilter {

    static final String DETAIL = "You must change your password before continuing (POST /api/v1/auth/password).";

    private final JsonMapper json;

    MustChangePasswordFilter(JsonMapper json) {
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (mustChangePassword(request) && !isAuthRoute(request)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, DETAIL));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean mustChangePassword(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(LoginSessions.MUST_CHANGE_PASSWORD));
    }

    private static boolean isAuthRoute(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/v1/auth/");
    }
}
