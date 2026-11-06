package com.xavier.backend.promotions.model.dto;

import java.util.List;
import java.util.UUID;

/**
 * The priced lines, one per request line, and the promotion release they were priced from ({@code releaseId} and
 * {@code version} are null when the store has no published promotion release).
 */
public record PricingResult(UUID releaseId, Long version, List<PricedLine> lines) {
}
