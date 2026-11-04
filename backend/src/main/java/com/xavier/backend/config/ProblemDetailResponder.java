package com.xavier.backend.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes 401/403 decided by the security filter chain (before any controller runs) in the same
 * RFC 9457 shape the {@code GlobalExceptionHandler} uses for everything else (D22).
 */
@Component
class ProblemDetailResponder {

    private final JsonMapper json;

    ProblemDetailResponder(JsonMapper json) {
        this.json = json;
    }

    AuthenticationEntryPoint unauthorized() {
        return (request, response, e) -> write(response, HttpStatus.UNAUTHORIZED, "Authentication is required.");
    }

    AccessDeniedHandler forbidden() {
        return (request, response, e) -> write(response, HttpStatus.FORBIDDEN, "You are not allowed to do this.");
    }

    private void write(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status, detail));
    }
}
