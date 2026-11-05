package com.xavier.backend.settlement.exception;

import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.shared.exception.ConflictException;
import java.util.Map;
import java.util.UUID;

/**
 * The request conflicts with the settlement state (409): an attempt of the order or session is still in doubt
 * ({@code PAYMENT_IN_DOUBT}, spec §7.4, §8.4), an attempt cannot take that transition ({@code ILLEGAL_ATTEMPT_STATE}),
 * or a payment method code is taken ({@code PAYMENT_METHOD_CODE_TAKEN}).
 */
public class SettlementConflictException extends ConflictException {

    private SettlementConflictException(String code, String message, Map<String, ?> properties) {
        super(code, message, properties);
    }

    public static SettlementConflictException inDoubt(String what, UUID id, UUID attemptId) {
        return new SettlementConflictException("PAYMENT_IN_DOUBT", "A payment attempt of " + what + " " + id
                + " is still open or in doubt; resolve it first.", Map.of("attemptId", attemptId));
    }

    public static SettlementConflictException illegalState(UUID attemptId, AttemptStatus status, String command) {
        return new SettlementConflictException("ILLEGAL_ATTEMPT_STATE", "Payment attempt " + attemptId + " is "
                + status + " and cannot " + command + ".", Map.of());
    }

    public static SettlementConflictException codeTaken(String code) {
        return new SettlementConflictException("PAYMENT_METHOD_CODE_TAKEN", "A payment method with code " + code
                + " already exists.", Map.of());
    }
}
