package com.xavier.backend.settlement.reconciliation.model.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * An imported statement (D78): {@code entries} counts its entries per kind (a kind with none is absent) and
 * {@code open} those without a resolution.
 */
public record BatchView(UUID id, UUID storeId, String methodCode, LocalDate periodFrom, LocalDate periodTo,
                        String source, String fileSha256, int lineCount, UUID importedByAccountId, Instant importedAt,
                        Map<String, Long> entries, long open) {
}
