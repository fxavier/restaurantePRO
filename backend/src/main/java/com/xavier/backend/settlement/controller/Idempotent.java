package com.xavier.backend.settlement.controller;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.shared.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;

/**
 * The settlement controllers' way into {@link IdempotencyService} (D24, D47, D63): the scope is the operation for the
 * current principal and the target is the request method and URI. Called from the controller, outside any
 * transaction. Each operation uses exactly one mode: {@link #execute} (one transaction) or {@link #steps} (D63: the
 * command has a step that calls an external system).
 */
final class Idempotent {

    private final IdempotencyService idempotency;
    private final AccessPolicy accessPolicy;

    Idempotent(IdempotencyService idempotency, AccessPolicy accessPolicy) {
        this.idempotency = idempotency;
        this.accessPolicy = accessPolicy;
    }

    <T> ResponseEntity<T> execute(String operation, String key, HttpServletRequest request, Object body,
                                  Class<T> type, Supplier<ResponseEntity<T>> action) {
        return idempotency.execute(accessPolicy.current().idempotencyScope(operation), key, target(request), body,
                type, action);
    }

    <T> ResponseEntity<T> steps(String operation, String key, HttpServletRequest request, Object body,
                                Class<T> type, Supplier<ResponseEntity<T>> steps) {
        return idempotency.executeSteps(accessPolicy.current().idempotencyScope(operation), key, target(request),
                body, type, steps);
    }

    private static String target(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }
}
