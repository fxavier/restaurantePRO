package com.xavier.backend.settlement.model.dto;

import java.time.Instant;
import java.util.UUID;

/** A sale document that is not issued yet: a fiscal pendency (D50 §7.6), with why the last issue failed. */
public record FiscalDraftView(UUID documentId, UUID orderId, String lastError, Instant lastErrorAt) {
}
