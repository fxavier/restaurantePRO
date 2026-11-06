package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/**
 * A DRAFT issued before settlement recorded its payment snapshot (D97 rule 4), or after {@code clearOnAccount}
 * cleared it. Issue turns it into 422 {@code FISCAL_PENDING}; its message is the draft's {@code lastError}, and the
 * retry, which refreshes the snapshot first, issues it.
 */
public class PaymentSnapshotMissingException extends BusinessRuleException {

    public static final String CODE = "PAYMENT_SNAPSHOT_MISSING";

    public PaymentSnapshotMissingException() {
        super(CODE, CODE);
    }
}
