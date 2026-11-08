package com.xavier.backend.orders.delivery.exception;

import com.xavier.backend.orders.DeliveryStatus;
import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/**
 * The request conflicts with a delivery's current state (409): the transition is not allowed from its status
 * ({@code DELIVERY_STATE}, D127: the POS re-reads it), or a zone code is taken in the store
 * ({@code DELIVERY_ZONE_CODE_TAKEN}). A stale {@code version} is a plain 409 as well.
 */
public class DeliveryConflictException extends ConflictException {

    private DeliveryConflictException(String code, String detail) {
        super(code, detail);
    }

    public static DeliveryConflictException state(UUID orderId, DeliveryStatus status, String action) {
        return new DeliveryConflictException("DELIVERY_STATE",
                "The delivery of order " + orderId + " is " + status + ": it cannot be " + action + ".");
    }

    public static DeliveryConflictException zoneCodeTaken(String code) {
        return new DeliveryConflictException("DELIVERY_ZONE_CODE_TAKEN",
                "The store already has a delivery zone " + code + ".");
    }
}
