package com.xavier.backend.cash;

import com.xavier.backend.shared.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * A confirmed settlement Refund to record in the refunder's cash session (A23). {@code methodCode} is the refunded
 * payment's method (D52); {@code refundId} is the movement's unique source, so recording it again changes nothing.
 */
public record RefundCommand(UUID sessionId, String methodCode, Money amount, UUID refundId, UUID actorEmployeeId) {

    public RefundCommand {
        Objects.requireNonNull(sessionId, "sessionId");
        MethodCount.requireMethodCode(methodCode);
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(actorEmployeeId, "actorEmployeeId");
    }
}
