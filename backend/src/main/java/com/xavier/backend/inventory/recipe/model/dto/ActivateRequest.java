package com.xavier.backend.inventory.recipe.model.dto;

import java.time.Instant;

/** When the version takes effect: now when absent; never in the past (D115). */
public record ActivateRequest(Instant effectiveFrom) {
}
