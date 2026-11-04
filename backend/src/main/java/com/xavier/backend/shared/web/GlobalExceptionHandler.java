package com.xavier.backend.shared.web;

import com.xavier.backend.shared.exception.BusinessRuleException;
import com.xavier.backend.shared.exception.ConflictException;
import com.xavier.backend.shared.exception.NotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single place that turns exceptions into RFC 9457 {@link ProblemDetail} responses (D22):
 * 400 validation (with {@code errors: [{field, message}]}) and unknown sort property, 401, 403, 404,
 * 409 conflict/stale version, 422 business rule, 500 without internals. Framework exceptions (405, 415, missing header…) keep
 * the {@link ResponseEntityExceptionHandler} defaults, which are already ProblemDetails.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** One invalid field of a request; {@code errors} property of a 400 ProblemDetail. */
    public record FieldViolation(String field, String message) {
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail staleVersion(OptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The resource was changed by someone else. Reload it and try again.");
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    /** A client {@code ?sort=} naming a property the entity does not have is the client's mistake, not ours. */
    @ExceptionHandler(PropertyReferenceException.class)
    ProblemDetail unknownSortProperty(PropertyReferenceException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Unknown sort property: " + e.getPropertyName());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail accessDenied(AccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You are not allowed to do this.");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail unauthenticated(AuthenticationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication is required.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::violation)
                .toList();
        ProblemDetail body = ex.getBody();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(
                                error instanceof FieldError fe ? fe.getField()
                                        : result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        ProblemDetail body = ex.getBody();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    private static FieldViolation violation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage());
    }
}
