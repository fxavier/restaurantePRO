package com.xavier.backend.shared.idempotency;

import com.xavier.backend.shared.exception.ConflictException;

/**
 * Another request with the same Idempotency-Key is still running (D24). Mapped to 409 with code
 * {@value #CODE}, so a client can tell "wait and resend the same key" from a version conflict (FE-F25).
 */
public class IdempotentRequestInProgressException extends ConflictException {

    public static final String CODE = "IDEMPOTENCY_IN_PROGRESS";

    public IdempotentRequestInProgressException() {
        super(CODE, "A request with this Idempotency-Key is still in progress. Retry later.");
    }
}
