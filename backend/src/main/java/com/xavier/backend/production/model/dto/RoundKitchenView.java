package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.model.KitchenStatus;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Where one round of an order is in the kitchen, for the POS order panel (ADR 0044): {@code batchId} is the order's
 * round, {@code status} that of its least advanced station, so a round is READY only when every station has it
 * READY or further. A round received before the kitchen display existed (V7013) is absent.
 */
@NullMarked
public record RoundKitchenView(UUID batchId, KitchenStatus status) {
}
