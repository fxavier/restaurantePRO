package com.xavier.backend.settlement.service;

import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;

/**
 * The one way to call a payment provider adapter outside a charge or a status query (D48), e.g. a statement fetch
 * (D78): with no transaction, on the provider pool, bounded by {@code restaurantpro.settlement.provider-timeout},
 * which startup checks is shorter than the idempotency step lease (so a D63 step never outlives its lease).
 */
@Component
public class BoundedProviderCalls {

    private final ProviderCalls calls;

    BoundedProviderCalls(ProviderCalls calls) {
        this.calls = calls;
    }

    /**
     * @throws ProviderUnavailableException when the provider does not answer in time or the pool is full
     * @throws IllegalStateException when a transaction is open
     */
    public <T> T call(String providerCode, String operation, Callable<T> call) {
        return calls.within(providerCode, operation, call);
    }
}
