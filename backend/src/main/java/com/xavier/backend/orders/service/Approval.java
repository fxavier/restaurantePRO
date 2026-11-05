package com.xavier.backend.orders.service;

import java.util.Objects;
import java.util.UUID;

/**
 * Phase 1 of an approved command (D40): {@code approverEmployeeId} was verified, outside any transaction, to approve
 * on {@code orderId}. Phase 2 accepts it for that order only.
 */
public record Approval(UUID orderId, UUID approverEmployeeId) {

    public Approval {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(approverEmployeeId, "approverEmployeeId");
    }

    void requireFor(UUID orderId) {
        if (!this.orderId.equals(orderId)) {
            throw new IllegalArgumentException("The approval is for order " + this.orderId + ", not " + orderId);
        }
    }
}
