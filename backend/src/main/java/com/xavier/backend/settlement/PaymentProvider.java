package com.xavier.backend.settlement;

/**
 * The integrated payment provider port (D53, P10). An adapter is a Spring bean; an {@code INTEGRATED} payment method
 * names it by {@link #code()}. R2 ships no production adapter (only {@code FakePaymentProvider} in test sources).
 *
 * <p>Settlement calls it with no transaction and no lock (D48, spec §7.3), after the attempt is persisted. Every
 * adapter's first line is {@code ExternalCalls.requireNoTransaction()}. Each call is bounded by
 * {@code restaurantpro.settlement.provider-timeout}: a call that does not answer in time counts as
 * {@link ProviderOutcome.Unknown}, never as a failure (§7.4).
 */
public interface PaymentProvider {

    /** The code an integrated payment method names, e.g. {@code MPESA}. */
    String code();

    /**
     * Charges {@code request.amount()}. {@code request.reference()} is the attempt id and must make the charge
     * idempotent at the provider: charging the same reference twice takes the money once.
     */
    ProviderOutcome charge(ChargeRequest request);

    /** What the provider knows about the charge with this reference (D50 §7.4: query before repeating). */
    ProviderOutcome status(String reference);
}
