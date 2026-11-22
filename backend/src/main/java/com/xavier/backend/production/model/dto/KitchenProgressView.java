package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.model.KitchenStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A round's progress on the kitchen display (ADR 0044). {@code version} goes back with the next command (stale →
 * 409). Each {@code *At}/{@code *By} pair is the time and employee of the step that reached the current status; a
 * recall clears the steps it undid and records itself in {@code recalledAt}/{@code recalledBy}.
 */
public record KitchenProgressView(KitchenStatus status, long version, Instant queuedAt, Instant startedAt,
                                  UUID startedBy, Instant readyAt, UUID readyBy, Instant bumpedAt, UUID bumpedBy,
                                  Instant recalledAt, UUID recalledBy) {
}
