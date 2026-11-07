package com.xavier.backend.promotions.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/** A promotions state conflict: 409 with a stable {@code code}. */
public class PromotionConflictException extends ConflictException {

    public PromotionConflictException(String code, String detail) {
        super(code, detail);
    }

    public static PromotionConflictException codeTaken(String code) {
        return new PromotionConflictException("PROMOTION_CODE_TAKEN", "The promotion code " + code + " is taken.");
    }

    /** The release is not in a state that allows the transition (D31), or another publication won. */
    public static PromotionConflictException releaseState(UUID releaseId, String detail) {
        return new PromotionConflictException("PROMOTION_RELEASE_STATE",
                "Promotion release " + releaseId + ": " + detail);
    }
}
