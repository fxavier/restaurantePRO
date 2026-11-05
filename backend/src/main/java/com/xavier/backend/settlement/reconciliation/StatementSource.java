package com.xavier.backend.settlement.reconciliation;

import java.time.LocalDate;
import java.util.List;

/**
 * An optional capability of a {@link com.xavier.backend.settlement.PaymentProvider} adapter (D78, PRD S31): it fetches
 * the provider's statement of a payment method for the days {@code [from, to]}, the same lines as the canonical CSV.
 * Reconciliation calls it with no transaction (D48, D63 {@code executeSteps}); every implementation's first line is
 * {@code ExternalCalls.requireNoTransaction()}. R3 ships none (only a test fake); a method whose provider bean does not
 * implement it is reconciled by importing its CSV.
 */
public interface StatementSource {

    List<StatementLine> statement(String methodCode, LocalDate from, LocalDate to);
}
