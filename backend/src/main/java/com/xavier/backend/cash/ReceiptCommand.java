package com.xavier.backend.cash;

import com.xavier.backend.shared.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * A confirmed settlement Payment to record in its cash session (spec §7.5). {@code methodCode} is the payment
 * method's code (D52); {@code paymentId} is the movement's unique source, so recording it again changes nothing.
 */
public record ReceiptCommand(UUID sessionId, String methodCode, Money amount, UUID paymentId, UUID actorEmployeeId) {

    public ReceiptCommand {
        Objects.requireNonNull(sessionId, "sessionId");
        MethodCount.requireMethodCode(methodCode);
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(actorEmployeeId, "actorEmployeeId");
    }
}
