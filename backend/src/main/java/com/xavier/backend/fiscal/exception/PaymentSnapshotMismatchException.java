package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.Map;
import java.util.UUID;

/**
 * A payment snapshot the DRAFT cannot take (422 {@code PAYMENT_SNAPSHOT_MISMATCH}, D97): its paid, credit and
 * outstanding amounts do not add up to the draft's gross, its status is not the one {@code PaymentStatus.of} gives, or
 * its credit contradicts the draft's on-account mark. A settlement bug, never a user error.
 */
public class PaymentSnapshotMismatchException extends BusinessRuleException {

    public static final String CODE = "PAYMENT_SNAPSHOT_MISMATCH";

    public PaymentSnapshotMismatchException(UUID documentId, String reason) {
        super(CODE, "The payment snapshot does not fit the draft document " + documentId + ": " + reason + ".",
                Map.of("documentId", documentId));
    }
}
