package com.xavier.backend.cash.controller;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.shared.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;

/**
 * The cash controllers' one way into {@link IdempotencyService} (D24, D47): the scope is the operation for the
 * current principal and the target is the request method and URI. Called from the controller, outside any
 * transaction.
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
        return idempotency.execute(accessPolicy.current().idempotencyScope(operation), key,
                request.getMethod() + " " + request.getRequestURI(), body, type, action);
    }
}
