package com.xavier.backend.organization.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * D182 as amended: at most {@value #LIMIT} enrolment attempts per source address in any {@link #WINDOW}; the next one is
 * refused (429) and not counted, so waiting a minute always works. Throttled attempts are counted
 * ({@code enrolment_throttled_total}, alertable, D149). In memory, per instance: an unauthenticated request has no
 * tenant, and the pool may not write a PLATFORM table (V1012), so n instances allow n × {@value #LIMIT} a minute; the
 * per-code burn (five failures, in the database) is what bounds guessing.
 */
@Component
class EnrolmentThrottle {

    static final int LIMIT = 10;
    static final Duration WINDOW = Duration.ofMinutes(1);
    /** Addresses kept before the idle ones are dropped. */
    private static final int PRUNE_ABOVE = 10_000;

    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Counter throttled;

    EnrolmentThrottle(Clock clock, MeterRegistry meters) {
        this.clock = clock;
        this.throttled = meters.counter("enrolment_throttled_total");
    }

    /** Records an attempt from {@code address}. @return false when it is over the limit (not recorded) */
    boolean tryAttempt(String address) {
        Instant now = clock.instant();
        if (attempts.size() > PRUNE_ABOVE) {
            attempts.values().removeIf(recent -> isIdle(recent, now));
        }
        Deque<Instant> recent = attempts.computeIfAbsent(address, ignored -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && !recent.peekFirst().isAfter(now.minus(WINDOW))) {
                recent.pollFirst();
            }
            if (recent.size() >= LIMIT) {
                throttled.increment();
                return false;
            }
            recent.addLast(now);
            return true;
        }
    }

    private static boolean isIdle(Deque<Instant> recent, Instant now) {
        synchronized (recent) {
            return recent.isEmpty() || !recent.peekLast().isAfter(now.minus(WINDOW));
        }
    }
}
