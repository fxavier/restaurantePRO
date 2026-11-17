package com.xavier.backend.identity.controller;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.identity.service.PinChangeService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * While a POS session's employee must replace a PIN a manager set (r2-decisions §12), only {@code /api/v1/auth/**} is
 * served (choose the PIN, read the session, log out); everything else gets a 403 that says why. Defence in depth:
 * such a session also holds no permission at all ({@code PrincipalActors}), so every use case refuses it anyway; this
 * filter only gives the client a clear answer. Registered by Spring Boot as a servlet filter after the security
 * chain, so it sees the resolved actor (and the POS chain's tenant).
 */
@Component
class MustChangePinFilter extends OncePerRequestFilter {

    static final String PIN_DETAIL = "You must change your PIN before continuing (POST /api/v1/auth/pos-pin).";

    private final AccessPolicy accessPolicy;
    private final PinChangeService pins;
    private final JsonMapper json;

    MustChangePinFilter(AccessPolicy accessPolicy, PinChangeService pins, JsonMapper json) {
        this.accessPolicy = accessPolicy;
        this.pins = pins;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Actor actor = accessPolicy.currentIfPresent().orElse(null);
        if (actor != null && actor.kind() == ActorKind.EMPLOYEE && !isAuthRoute(request)
                && pins.mustChangePin(actor.employeeId())) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                    PIN_DETAIL));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isAuthRoute(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/v1/auth/");
    }
}
