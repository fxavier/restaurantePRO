package com.xavier.bff;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.io.IOException;

/** The BFF's own ProblemDetail codes; everything else the SPA sees comes from the backend unchanged. */
enum BffProblem {

    /** No login, or the session's tokens can no longer be used or refreshed: the SPA logs in again. */
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "BFF_SESSION_EXPIRED", "The BFF session has no valid token; log in again"),
    /** Keycloak did not answer the refresh in time, or answered 5xx: the session is kept and the SPA retries later. */
    IDP_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "IDP_UNAVAILABLE", "The identity provider could not be reached; try again"),
    BACKEND_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "BACKEND_UNAVAILABLE", "The backend could not be reached"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "BFF_PAYLOAD_TOO_LARGE", "The request body is larger than the BFF relays");

    private final HttpStatus status;
    private final String code;
    private final String detail;

    BffProblem(HttpStatus status, String code, String detail) {
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    private ProblemDetail toProblemDetail() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }

    ResponseEntity<ProblemDetail> toResponse() {
        return ResponseEntity.status(status).body(toProblemDetail());
    }

    /** For the security filter chain, which answers before Spring MVC can render a ProblemDetail. */
    void writeTo(HttpServletResponse response) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"code\":\"%s\"}"
                .formatted(status.getReasonPhrase(), status.value(), detail, code));
    }
}
