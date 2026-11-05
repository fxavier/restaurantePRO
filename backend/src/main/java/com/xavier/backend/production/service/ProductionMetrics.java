package com.xavier.backend.production.service;

import com.xavier.backend.production.model.PrintJobStatus;
import com.xavier.backend.production.repository.PrintJobRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code print_jobs{status}} (a gauge read from the queue on each scrape) and {@code production_unrouted_total}
 * (work with a destination or terminal that has no printer, D56).
 */
@Service
public class ProductionMetrics {

    static final String UNROUTED = "production.unrouted";

    private static final Logger log = LoggerFactory.getLogger(ProductionMetrics.class);

    private final Counter unrouted;

    ProductionMetrics(MeterRegistry registry, PrintJobRepository jobs) {
        for (PrintJobStatus status : PrintJobStatus.values()) {
            Gauge.builder("print_jobs", jobs, repository -> repository.countByStatus(status))
                    .tag("status", status.name()).description("Print jobs by status").register(registry);
        }
        this.unrouted = Counter.builder(UNROUTED).description("Production work with no printer to print on")
                .register(registry);
    }

    /** Logged at WARN and counted: the work is not printed (D56). */
    void unrouted(UUID storeId, Object source, UUID sourceId, String where) {
        log.warn("Not printed: no printer for {} in store {} ({} {})", where, storeId, source, sourceId);
        unrouted.increment();
    }
}
