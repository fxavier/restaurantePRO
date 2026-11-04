package com.xavier.backend.shared.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * D47.4: Idempotency-Key records older than {@code restaurantpro.idempotency.retention} (default {@code P90D}) are
 * deleted once a day: completed ones by their completion time, and claims still {@code IN_PROGRESS} by their creation
 * time (abandoned after a crash, or by an action that was refused, e.g. a 422, which stores nothing), except a
 * claim whose step lease is still live (D63: its owner is running). POS keys are
 * per-action UUIDs, so nobody retries after that. Safe under overlap (one statement, D46).
 */
@Service
class IdempotencyRetention {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRetention.class);

    private final IdempotencyRecordStore store;
    private final Clock clock;
    private final Duration retention;

    IdempotencyRetention(IdempotencyRecordStore store, Clock clock,
                         @Value("${restaurantpro.idempotency.retention:P90D}") Duration retention) {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("restaurantpro.idempotency.retention must be positive: " + retention);
        }
        this.store = store;
        this.clock = clock;
        this.retention = retention;
    }

    /** @return how many records were deleted */
    @Scheduled(cron = "${restaurantpro.idempotency.retention-cron:0 30 3 * * *}")
    int purgeExpired() {
        Instant now = clock.instant();
        int deleted = store.deleteExpiredBefore(now.minus(retention), now);
        log.info("Idempotency retention deleted {} records (completed or abandoned) older than {}", deleted, retention);
        return deleted;
    }
}
