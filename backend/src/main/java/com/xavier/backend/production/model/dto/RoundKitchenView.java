package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.model.RoundKitchenState;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Where one round of an order is in the kitchen, for the POS order panel (ADR 0044): {@code batchId} is the order's
 * round; {@code status} is READY when every station it went to has it READY or BUMPED, IN_PREPARATION once any station
 * has started it but not every one has it ready, else QUEUED ({@link RoundKitchenState}). A round received before the
 * kitchen display existed (V7013) is absent.
 */
@NullMarked
public record RoundKitchenView(UUID batchId, RoundKitchenState status) {
}
