package com.xavier.backend.shared.idempotency;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** The same Idempotency-Key was sent with a different request body (D24). Mapped to 422. */
public class IdempotencyKeyReusedException extends BusinessRuleException {

    public IdempotencyKeyReusedException() {
        super("This Idempotency-Key was already used with a different request.");
    }
}
