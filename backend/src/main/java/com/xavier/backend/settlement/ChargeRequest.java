package com.xavier.backend.settlement;

import com.xavier.backend.shared.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * A charge for {@link PaymentProvider#charge}. {@code reference} is the payment attempt id, persisted before the call
 * (§7.3), and is the provider-side idempotency reference (D53).
 */
public record ChargeRequest(String reference, Money amount, UUID storeId, UUID orderId) {

    public ChargeRequest {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(orderId, "orderId");
    }
}
