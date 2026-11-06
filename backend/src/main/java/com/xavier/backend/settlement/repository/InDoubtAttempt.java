package com.xavier.backend.settlement.repository;

import java.time.Instant;
import java.util.UUID;

/** An UNKNOWN attempt as the in-doubt gauge reads it (D81 (4)): its store and since when it exists. */
public record InDoubtAttempt(UUID id, UUID storeId, Instant createdAt) {
}
