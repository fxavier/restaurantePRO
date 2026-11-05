package com.xavier.backend.settlement.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A sale document that is not issued yet: a fiscal pendency (D50 §7.6), with why the last issue failed.
 * {@code sourceType} is {@code ORDER} (the order's residual document) or {@code PARTITION} (an item partition's own
 * document, R3), with its {@code sourceId}.
 */
public record FiscalDraftView(UUID documentId, UUID orderId, String lastError, Instant lastErrorAt,
                              String sourceType, UUID sourceId) {
}
