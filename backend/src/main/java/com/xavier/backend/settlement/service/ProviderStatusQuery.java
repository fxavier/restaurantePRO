package com.xavier.backend.settlement.service;

import com.xavier.backend.settlement.PaymentProvider;
import com.xavier.backend.settlement.ProviderOutcome;
import com.xavier.backend.settlement.model.PaymentAttempt;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The resolve's step s2 (D50 §7.4, D63, D81), shared by the operator's resolve and the {@link InDoubtResolver}: the
 * attempt's provider is asked about its reference with no transaction, through {@link ProviderCalls} (bounded; a
 * timeout is {@code Unknown}). An attempt whose provider bean is gone gets {@code Unknown}.
 */
@Component
class ProviderStatusQuery {

    private final PaymentProviders providers;
    private final ProviderCalls calls;

    ProviderStatusQuery(PaymentProviders providers, ProviderCalls calls) {
        this.providers = providers;
        this.calls = calls;
    }

    Answer ask(PaymentAttempt attempt) {
        return providers.find(attempt.getProviderCode())
                .map(provider -> new Answer(calls.status(provider, attempt.getProviderReference()),
                        provider.acceptanceWindow()))
                .orElseGet(() -> new Answer(new ProviderOutcome.Unknown("no provider " + attempt.getProviderCode()),
                        Duration.ZERO));
    }

    /** The provider's answer and its {@link PaymentProvider#acceptanceWindow()}. */
    record Answer(ProviderOutcome outcome, Duration acceptanceWindow) {
    }
}
