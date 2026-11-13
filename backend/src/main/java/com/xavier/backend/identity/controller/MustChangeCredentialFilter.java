package com.xavier.backend.identity.controller;

import com.xavier.backend.identity.service.MfaSessionAttributes;
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
 * While a session's account must change its password (D30), or its employee must replace a PIN a manager set
 * (r2-decisions §12), or its login waits for a second factor or an enrolment (D141: {@code MFA_PENDING},
 * {@code MFA_ENROLMENT_REQUIRED}), only {@code /api/v1/auth/**} is served (change it, verify, enrol, read the session,
 * log out, log in as someone else); everything else gets a 403 that says why.
 *
 * <p>Defence in depth: such a session also holds no permission at all (see {@code LoginService.sessionOf} and
 * {@code PosLoginService}), so every use case and the actuator rule refuse it anyway; this filter only gives the
 * client a clear answer. Registered by Spring Boot as a servlet filter after the security filter chain, so CSRF and
 * authentication have already been checked when it runs.
 */
@Component
class MustChangeCredentialFilter extends OncePerRequestFilter {

    static final String PASSWORD_DETAIL =
            "You must change your password before continuing (POST /api/v1/auth/password).";
    static final String PIN_DETAIL = "You must change your PIN before continuing (POST /api/v1/auth/pos-pin).";
    static final String MFA_PENDING_DETAIL =
            "Confirm your second factor before continuing (POST /api/v1/auth/mfa/verify).";
    static final String MFA_ENROLMENT_DETAIL =
            "Set up a second factor before continuing (POST /api/v1/auth/mfa/enrolment).";

    private final JsonMapper json;

    MustChangeCredentialFilter(JsonMapper json) {
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String refusal = isAuthRoute(request) ? null : refusal(request.getSession(false));
        if (refusal != null) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, refusal));
            return;
        }
        chain.doFilter(request, response);
    }

    private static String refusal(HttpSession session) {
        if (session == null) {
            return null;
        }
        if (Boolean.TRUE.equals(session.getAttribute(LoginSessions.MUST_CHANGE_PASSWORD))) {
            return PASSWORD_DETAIL;
        }
        if (Boolean.TRUE.equals(session.getAttribute(LoginSessions.MUST_CHANGE_PIN))) {
            return PIN_DETAIL;
        }
        return switch (MfaSessionAttributes.state(session)) {
            case PENDING -> MFA_PENDING_DETAIL;
            case ENROLMENT_REQUIRED -> MFA_ENROLMENT_DETAIL;
            case NONE, VERIFIED -> null;
        };
    }

    private static boolean isAuthRoute(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/v1/auth/");
    }
}
