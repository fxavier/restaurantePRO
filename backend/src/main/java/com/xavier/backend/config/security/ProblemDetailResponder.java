package com.xavier.backend.config.security;

import com.xavier.backend.access.StepUpRequiredException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes what the security filter chains decide before any controller runs (401, 403, 503, the tenant refusals) in
 * the same RFC 9457 shape the {@code GlobalExceptionHandler} uses for everything else (D22), each refusal with its
 * stable {@code code}. It also renders the step-up refusal (D184) wherever it is thrown: {@code shared}'s handler
 * cannot know {@code access} types, so this advice runs before it ({@link Ordered#HIGHEST_PRECEDENCE}).
 *
 * <p>The step-up challenge is RFC 9470 §3: 401 with {@code WWW-Authenticate: Bearer
 * error="insufficient_user_authentication", error_description="…", acr_values="2", max_age=<seconds>}; a POS (PIN)
 * session that cannot step up gets 403 {@code MFA_ACCOUNT_REQUIRED} instead.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProblemDetailResponder {

    static final String IDP_UNAVAILABLE = "IDP_UNAVAILABLE";
    static final String INVALID_TOKEN = "Bearer error=\"invalid_token\", "
            + "error_description=\"The access token is not valid\"";
    static final String BEARER = "Bearer";

    private final JsonMapper json;
    private final long stepUpMaxAgeSeconds;

    ProblemDetailResponder(JsonMapper json, SecurityProperties properties) {
        this.json = json;
        this.stepUpMaxAgeSeconds = properties.stepUpMaxAge().toSeconds();
    }

    /** No credential: 401 with the plain Bearer challenge. */
    public AuthenticationEntryPoint unauthorized() {
        return (request, response, e) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER);
            write(response, ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication is required."));
        };
    }

    public AccessDeniedHandler forbidden() {
        return (request, response, e) -> {
            if (e instanceof StepUpRequiredException stepUp) {
                ResponseEntity<ProblemDetail> entity = stepUpRequired(stepUp);
                entity.getHeaders().forEach((name, values) -> values.forEach(v -> response.addHeader(name, v)));
                write(response, entity.getBody());
            } else {
                write(response, ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                        "You are not allowed to do this."));
            }
        };
    }

    /** A token that is not a valid access token (D176): 401 with {@code error="invalid_token"}. */
    void invalidToken(HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, INVALID_TOKEN);
        write(response, ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "The access token is not valid."));
    }

    /** No trusted issuer, or no key cached and the key set unreachable (D176): fail closed. */
    void idpUnavailable(HttpServletResponse response) throws IOException {
        write(response, coded(HttpStatus.SERVICE_UNAVAILABLE, IDP_UNAVAILABLE,
                "The identity provider is unavailable; try again later."));
    }

    /** A refusal of the request pipeline with its stable code (tenant resolution, POS session). */
    void refuse(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER);
        }
        write(response, coded(status, code, detail));
    }

    /** The RFC 9470 challenge, written by a filter (a support request without a second factor, D183). */
    void stepUpChallenge(HttpServletResponse response) throws IOException {
        StepUpRequiredException e = StepUpRequiredException.stepUpRequired();
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge());
        write(response, body(e, HttpStatus.UNAUTHORIZED));
    }

    /** 401 + the RFC 9470 challenge, or 403 {@code MFA_ACCOUNT_REQUIRED} for a session that cannot step up. */
    @ExceptionHandler(StepUpRequiredException.class)
    ResponseEntity<ProblemDetail> stepUpRequired(StepUpRequiredException e) {
        if (!e.canStepUp()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body(e, HttpStatus.FORBIDDEN));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, challenge())
                .body(body(e, HttpStatus.UNAUTHORIZED));
    }

    private String challenge() {
        return "Bearer error=\"insufficient_user_authentication\", "
                + "error_description=\"A different authentication level is required\", acr_values=\""
                + StepUpRequiredException.REQUIRED_ACR + "\", max_age=" + stepUpMaxAgeSeconds;
    }

    private ProblemDetail body(StepUpRequiredException e, HttpStatus status) {
        ProblemDetail problem = coded(status, e.code(), e.getMessage());
        if (e.canStepUp()) {
            problem.setProperty("acrValues", StepUpRequiredException.REQUIRED_ACR);
            problem.setProperty("maxAge", stepUpMaxAgeSeconds);
        }
        return problem;
    }

    private static ProblemDetail coded(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
