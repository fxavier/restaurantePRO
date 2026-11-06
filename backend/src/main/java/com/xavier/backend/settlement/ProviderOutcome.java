package com.xavier.backend.settlement;

/** What a {@link PaymentProvider} answers (D53). Anything but {@link Succeeded} or {@link Failed} is not final. */
public sealed interface ProviderOutcome {

    /** The money was taken; {@code providerRef} is the provider's own transaction reference. */
    record Succeeded(String providerRef) implements ProviderOutcome {
    }

    /** The provider refused the charge; nothing was taken. */
    record Failed(String reason) implements ProviderOutcome {
    }

    /** The provider accepted the request and will decide later (ask {@code status}). */
    record Pending() implements ProviderOutcome {
    }

    /** The outcome is not known (timeout, broken connection): query before repeating (§7.4). */
    record Unknown(String detail) implements ProviderOutcome {
    }

    /**
     * D81 (1): the provider does not know the reference. A charge may still be on its way, so it proves nothing was
     * taken only for an UNKNOWN attempt older than {@link PaymentProvider#acceptanceWindow()}; it stays UNKNOWN
     * otherwise.
     */
    record NotFound() implements ProviderOutcome {
    }
}
