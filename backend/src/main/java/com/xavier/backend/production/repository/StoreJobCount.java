package com.xavier.backend.production.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Print jobs of one store in a condition (D150): how many, how many of them are late (waiting jobs only), and the
 * oldest one's creation.
 */
public record StoreJobCount(UUID storeId, Long jobs, Long late, Instant oldestCreatedAt) {
}
