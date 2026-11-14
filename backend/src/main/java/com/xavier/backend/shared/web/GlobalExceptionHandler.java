package com.xavier.backend.shared.web;

import com.xavier.backend.shared.exception.BusinessRuleException;
import com.xavier.backend.shared.exception.CodedException;
import com.xavier.backend.shared.exception.ConflictException;
import com.xavier.backend.shared.exception.NotFoundException;
import com.xavier.backend.shared.exception.UnavailableException;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.xavier.backend.shared.persistence.StoreScopeViolations.Kind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.query.sqm.UnknownPathException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.InvalidDataAccessApiUsageException;
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
 * 409 conflict/stale version, 422 business rule, 500 without internals. A 404/409/422 from a shared exception
 * carries its stable {@code code} and extra properties ({@link CodedException}). Framework exceptions (405, 415, missing header…) keep
 * the {@link ResponseEntityExceptionHandler} defaults, which are already ProblemDetails. A refusal by row-level
 * security (D170) is a coded 500 ({@code STORE_SCOPE_MISSING}, {@code STORE_SCOPE_VIOLATION},
 * {@code STORE_CHANGE_REFUSED}), counted and logged with its scope origin; the body carries only the code (the
 * correlation id is in the response header), never a store id or the SQL.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int MAX_ECHOED_PROPERTY = 64;
    private static final Pattern ORIGIN = Pattern.compile("\\(origin ([^)]{1,200})\\)");
    private static final Map<Kind, String> STORE_SCOPE_DETAIL = Map.of(
            Kind.SCOPE_MISSING, "Unexpected error: no store scope.",
            Kind.WRITE_REFUSED, "Unexpected error: a row outside the store scope was refused.",
            Kind.STORE_CHANGE, "Unexpected error: a row cannot move to another store.");

    private final StoreScopeViolations violations;

    public GlobalExceptionHandler(StoreScopeViolations violations) {
        this.violations = violations;
    }

    /** One invalid field of a request; {@code errors} property of a 400 ProblemDetail. */
    public record FieldViolation(String field, String message) {
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail staleVersion(OptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The resource was changed by someone else. Reload it and try again.");
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, e);
    }

    @ExceptionHandler(UnavailableException.class)
    ProblemDetail unavailable(UnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, e);
    }

    /** {@code detail} is the message; the stable {@code code} (when set) and the extra properties are added. */
    private static ProblemDetail problem(HttpStatus status, CodedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        if (e.code() != null) {
            problem.setProperty("code", e.code());
        }
        e.properties().forEach(problem::setProperty);
        return problem;
    }

    /**
     * A client {@code ?sort=} naming a property the entity does not have is the client's mistake, not ours.
     * Spring Data checks the name for findAll and derived queries; the shape is checked by {@link SortParameterGuard}.
     */
    @ExceptionHandler(PropertyReferenceException.class)
    ProblemDetail unknownSortProperty(PropertyReferenceException e) {
        String property = e.getPropertyName();
        String echoed = property.length() <= MAX_ECHOED_PROPERTY ? property
                : property.substring(0, MAX_ECHOED_PROPERTY) + "...";
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Unknown sort property: " + echoed);
    }

    /**
     * For an {@code @Query} list Spring Data appends the client's sort unchecked and Hibernate rejects the path at
     * query time. Every query is validated at startup and none is built at runtime, so an unknown path here can only
     * come from the client's sort. Hibernate's message names the entity class, so it is not echoed.
     */
    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    ProblemDetail invalidDataAccessApiUsage(InvalidDataAccessApiUsageException e) {
        if (e.contains(UnknownPathException.class)) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Unknown sort property.");
        }
        return unexpected(e);
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
        Optional<Kind> refusal = StoreScopeViolations.classify(e);
        if (refusal.isPresent()) {
            return storeScopeRefusal(refusal.get(), e);
        }
        log.error("Unhandled exception", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error.");
    }

    private ProblemDetail storeScopeRefusal(Kind kind, Exception e) {
        violations.record(kind, originOf(e));
        log.error("Row-level security refused the request ({})", kind.code(), e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                STORE_SCOPE_DETAIL.get(kind));
        problem.setProperty("code", kind.code());
        return problem;
    }

    /** The scope origin the database quotes in RS001/RS002 messages; a 42501 does not name it. */
    private static String originOf(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            Matcher origin = ORIGIN.matcher(String.valueOf(cause.getMessage()));
            if (origin.find()) {
                return origin.group(1);
            }
        }
        return "request";
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
