package com.xavier.backend.production.model;

import java.util.Collection;
import java.util.Comparator;

/**
 * Where a round is on the kitchen display (ADR 0044): QUEUED → IN_PREPARATION → READY → BUMPED (taken off the
 * screen: served or handed over). A recall steps back once: READY → IN_PREPARATION, BUMPED → READY. Declared in that
 * order, which {@link #leastAdvanced} relies on.
 */
public enum KitchenStatus {
    QUEUED,
    IN_PREPARATION,
    READY,
    BUMPED;

    /** A round on several stations is only as far along as its least advanced one. */
    public static KitchenStatus leastAdvanced(Collection<KitchenStatus> stations) {
        return stations.stream().min(Comparator.naturalOrder()).orElseThrow();
    }
}
