package com.xavier.bridge;

import java.time.Duration;

/** The wait after a failed cycle: {@code first}, doubling, at most {@code max}; a success starts again (review 2). */
final class Backoff {

    private final Duration first;
    private final Duration max;
    private Duration next;

    Backoff(Duration first, Duration max) {
        this.first = first;
        this.max = max;
        this.next = first;
    }

    Duration next() {
        Duration current = next;
        Duration doubled = next.multipliedBy(2);
        next = doubled.compareTo(max) > 0 ? max : doubled;
        return current;
    }

    void reset() {
        next = first;
    }
}
