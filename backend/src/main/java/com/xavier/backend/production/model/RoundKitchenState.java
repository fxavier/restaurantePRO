package com.xavier.backend.production.model;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Where a round is in the kitchen as the POS order panel shows it (ADR 0044), from the progress of each station it went
 * to: SERVED ("servida") when every station has BUMPED it; READY ("pronta") when every station has it READY or BUMPED
 * but not all have bumped it; IN_PREPARATION ("em preparação") once any station has started it but not every station
 * has it ready; QUEUED while no station has started it.
 */
public enum RoundKitchenState {
    QUEUED,
    IN_PREPARATION,
    READY,
    SERVED;

    private static final Set<KitchenStatus> DONE = EnumSet.of(KitchenStatus.READY, KitchenStatus.BUMPED);

    /** The round's state from its stations' progress (at least one). */
    public static RoundKitchenState of(Collection<KitchenStatus> stations) {
        if (stations.isEmpty()) {
            throw new IllegalArgumentException("a round has at least one station");
        }
        if (stations.stream().allMatch(station -> station == KitchenStatus.BUMPED)) {
            return SERVED;
        }
        if (DONE.containsAll(stations)) {
            return READY;
        }
        return stations.stream().anyMatch(station -> station != KitchenStatus.QUEUED) ? IN_PREPARATION : QUEUED;
    }
}
