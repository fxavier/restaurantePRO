package com.xavier.backend.settlement.exception;

import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.shared.exception.ConflictException;
import java.util.Map;
import java.util.UUID;

/**
 * The request conflicts with the settlement state (409): an attempt of the order or session is still in doubt
 * ({@code PAYMENT_IN_DOUBT}, spec §7.4, §8.4), an attempt cannot take that transition ({@code ILLEGAL_ATTEMPT_STATE}),
 * a bill partition cannot take that transition ({@code ILLEGAL_PARTITION_STATE}), a payment method code is taken
 * ({@code PAYMENT_METHOD_CODE_TAKEN}), a credit charge cannot take that transition ({@code ILLEGAL_CHARGE_STATE}), or
 * the target already has a live credit charge for another customer or amount ({@code CREDIT_CHARGE_EXISTS}, D74).
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

    public static SettlementConflictException illegalPartitionState(UUID partitionId, Object status,
                                                                    String command) {
        return new SettlementConflictException("ILLEGAL_PARTITION_STATE", "Bill partition " + partitionId + " is "
                + status + " and cannot " + command + ".", Map.of());
    }

    public static SettlementConflictException codeTaken(String code) {
        return new SettlementConflictException("PAYMENT_METHOD_CODE_TAKEN", "A payment method with code " + code
                + " already exists.", Map.of());
    }

    public static SettlementConflictException illegalChargeState(UUID chargeId, Object status, String command) {
        return new SettlementConflictException("ILLEGAL_CHARGE_STATE", "Credit charge " + chargeId + " is " + status
                + " and cannot " + command + ".", Map.of());
    }

    public static SettlementConflictException chargeExists(String what, UUID targetId, UUID chargeId) {
        return new SettlementConflictException("CREDIT_CHARGE_EXISTS", "The " + what + " " + targetId
                + " already has a credit charge; cancel it first.", Map.of("chargeId", chargeId));
    }
}
