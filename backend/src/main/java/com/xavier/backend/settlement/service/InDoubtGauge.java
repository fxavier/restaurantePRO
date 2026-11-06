package com.xavier.backend.settlement.service;

import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Gauge {@code payment_attempts_in_doubt}: the UNKNOWN attempts waiting for a resolve (PRD S16, §7.4). */
@Component
class InDoubtGauge {

    InDoubtGauge(MeterRegistry meters, PaymentAttemptRepository attempts) {
        Gauge.builder("payment_attempts_in_doubt", attempts,
                        repository -> repository.countByStatus(AttemptStatus.UNKNOWN))
                .description("Payment attempts whose outcome is unknown and must be resolved")
                .register(meters);
    }
}
