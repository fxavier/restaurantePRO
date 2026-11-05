package com.xavier.backend.settlement.reconciliation.model;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;

/**
 * One outcome of matching (D78): a statement {@code row} (absent for MISSING_PROVIDER) and the {@code payment} it is
 * about (absent for MISSING_INTERNAL).
 */
public record Match(EntryKind kind, StatementRow row, PaymentCandidate payment) {

    /** The payment's net amount, counted for MATCHED, AMOUNT_MISMATCH and MISSING_PROVIDER only. */
    public Money internalAmount() {
        return switch (kind) {
            case MATCHED, AMOUNT_MISMATCH, MISSING_PROVIDER -> payment.net();
            case MISSING_INTERNAL, DUPLICATE_LINE -> null;
        };
    }

    public Money providerAmount() {
        return row == null ? null : row.amount();
    }

    /** What the provider has more than we count: provider − internal, an absent side counting as zero. */
    public Money difference() {
        Money provider = providerAmount();
        Money internal = internalAmount();
        Money any = provider != null ? provider : internal;
        Money zero = Money.of(BigDecimal.ZERO, any.currency());
        return (provider == null ? zero : provider).minus(internal == null ? zero : internal);
    }
}
