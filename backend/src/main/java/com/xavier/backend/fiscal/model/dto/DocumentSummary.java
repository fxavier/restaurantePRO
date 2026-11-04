package com.xavier.backend.fiscal.model.dto;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.UUID;

/** One row of the BackOffice document list. */
public record DocumentSummary(UUID id, UUID storeId, String documentType, String status, UUID sourceId,
                              String seriesCode, Long number, Instant issuedAt, Money totalGross, String buyerName) {
}
