package com.xavier.backend.config.persistence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads {@code db_transactions{origin}} (S77): the transactions begun so far, per origin kind. Take a {@link #snapshot}
 * before the work and ask {@link #since} after it.
 */
public final class TransactionCount {

    private final MeterRegistry registry;

    public TransactionCount(MeterRegistry registry) {
        this.registry = registry;
    }

    /** The count per origin kind now. */
    public Map<String, Long> snapshot() {
        Map<String, Long> counts = new HashMap<>();
        for (Counter counter : registry.find(TenantBindingTransactionManager.TRANSACTIONS).counters()) {
            counts.merge(counter.getId().getTag(TenantBindingTransactionManager.ORIGIN_TAG),
                    (long) counter.count(), Long::sum);
        }
        return counts;
    }

    /** The transactions begun since {@code before}, per origin kind (kinds with none left out). */
    public Map<String, Long> since(Map<String, Long> before) {
        Map<String, Long> delta = new HashMap<>();
        snapshot().forEach((origin, count) -> {
            long begun = count - before.getOrDefault(origin, 0L);
            if (begun != 0) {
                delta.put(origin, begun);
            }
        });
        return delta;
    }
}
