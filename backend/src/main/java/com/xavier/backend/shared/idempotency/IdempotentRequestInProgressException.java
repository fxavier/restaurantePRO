package com.xavier.backend.shared.idempotency;

import com.xavier.backend.shared.exception.ConflictException;

/** Another request with the same Idempotency-Key is still running (D24). Mapped to 409. */
public class IdempotentRequestInProgressException extends ConflictException {

    public IdempotentRequestInProgressException() {
        super("A request with this Idempotency-Key is still in progress. Retry later.");
    }
}
