package com.xavier.backend.inventory.preparation.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/** A preparation command its state does not allow: 409 {@code PREPARATION_STATE}. */
public class PreparationConflictException extends ConflictException {

    public PreparationConflictException(String code, String message) {
        super(code, message);
    }

    public static PreparationConflictException state(UUID preparationId, Object status, String action) {
        return new PreparationConflictException("PREPARATION_STATE",
                "Preparation " + preparationId + " is " + status + " and cannot " + action + ".");
    }
}
