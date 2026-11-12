package com.xavier.backend.config;

import com.xavier.backend.access.StepUpRequiredException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes 401/403 decided by the security filter chain (before any controller runs) in the same
 * RFC 9457 shape the {@code GlobalExceptionHandler} uses for everything else (D22).
 *
 * <p>It also renders the D142 step-up refusal, wherever it is thrown: {@code shared}'s handler cannot know
 * {@code access} types, so this advice runs before it ({@link Ordered#HIGHEST_PRECEDENCE}) for
 * {@link StepUpRequiredException} only, and every other 403 keeps the plain shape.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProblemDetailResponder {

    private final JsonMapper json;

    ProblemDetailResponder(JsonMapper json) {
        this.json = json;
    }

    AuthenticationEntryPoint unauthorized() {
        return (request, response, e) -> write(response,
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication is required."));
    }

    AccessDeniedHandler forbidden() {
        return (request, response, e) -> write(response, e instanceof StepUpRequiredException stepUp
                ? stepUpRequired(stepUp)
                : ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You are not allowed to do this."));
    }

    /** 403 with the stable {@code code} and where to prove the second factor ({@code stepUpUri}). */
    @ExceptionHandler(StepUpRequiredException.class)
    ProblemDetail stepUpRequired(StepUpRequiredException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        problem.setProperty("code", e.code());
        problem.setProperty("stepUpUri", StepUpRequiredException.STEP_UP_URI);
        return problem;
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
