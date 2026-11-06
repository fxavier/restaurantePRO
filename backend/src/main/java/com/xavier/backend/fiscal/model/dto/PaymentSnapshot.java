package com.xavier.backend.fiscal.model.dto;

import com.xavier.backend.shared.Money;
import java.util.List;
import java.util.Objects;

/**
 * The payment breakdown of a document at its issue (D97 rule 2), frozen with it: {@code methods} are what was paid
 * per method on the targets it covers (net of refunds, positive amounts only, in the order each method was first
 * confirmed), {@code credit} what live credit charges put on account, and {@code outstanding = gross − paid −
 * credit}. Settlement computes it; fiscal stores it on the DRAFT and prints it.
 */
public record PaymentSnapshot(PaymentStatus status, List<PaidAmount> methods, Money credit, Money outstanding) {

    public PaymentSnapshot {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(credit, "credit");
        Objects.requireNonNull(outstanding, "outstanding");
        methods = methods == null ? List.of() : List.copyOf(methods);
    }

    /** Σ of the paid methods. */
    public Money paid() {
        return methods.stream().map(PaidAmount::amount).reduce(Money.of(java.math.BigDecimal.ZERO,
                credit.currency()), Money::plus);
    }

    /** One payment method's share: {@code label} is the method's display name at the snapshot ("Numerário"). */
    public record PaidAmount(String methodCode, String label, Money amount) {
    }
}
