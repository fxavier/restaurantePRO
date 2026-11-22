package com.xavier.backend.production.model;

/**
 * Where a round is on the kitchen display (ADR 0044): QUEUED → IN_PREPARATION → READY → BUMPED (taken off the
 * screen: served or handed over). A recall steps back once: READY → IN_PREPARATION, BUMPED → READY.
 */
public enum KitchenStatus {
    QUEUED,
    IN_PREPARATION,
    READY,
    BUMPED
}
