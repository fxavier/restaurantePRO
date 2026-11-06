package com.xavier.backend.access;

import com.xavier.backend.shared.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Every controller's one way into {@link IdempotencyService} (D24, D47, D63). The scope is the operation for the
 * current principal ({@link Actor#idempotencyScope}) and the target is the request method and URI. Call it from the
 * controller, outside any transaction. Each operation uses exactly one mode: {@link #execute} (the command is one
 * transaction) or {@link #steps} (D63: the command has a step that calls an external system).
 *
 * <p>It lives in {@code access}, not in {@code shared.idempotency}, because deriving the scope needs the current
 * {@link Actor}, and {@code shared} depends on no other module.
 */
@Component
public class IdempotentRequests {

    private final IdempotencyService idempotency;
    private final AccessPolicy accessPolicy;

    IdempotentRequests(IdempotencyService idempotency, AccessPolicy accessPolicy) {
        this.idempotency = idempotency;
        this.accessPolicy = accessPolicy;
    }

    /** Runs {@code action} once per {@code (scope, key)}; a replay with the same body returns the stored response. */
    public <T> ResponseEntity<T> execute(String operation, String key, HttpServletRequest request, Object body,
                                         Class<T> type, Supplier<ResponseEntity<T>> action) {
        return idempotency.execute(accessPolicy.current().idempotencyScope(operation), key, target(request), body,
                type, action);
    }

    /** As {@link #execute}, for a command whose steps call an external system (D63, {@code executeSteps}). */
    public <T> ResponseEntity<T> steps(String operation, String key, HttpServletRequest request, Object body,
                                       Class<T> type, Supplier<ResponseEntity<T>> steps) {
        return idempotency.executeSteps(accessPolicy.current().idempotencyScope(operation), key, target(request),
                body, type, steps);
    }

    private static String target(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }
}
