package com.xavier.backend.fiscal.model.dto;

import java.util.UUID;

/** A numbering series as the BackOffice sees it. {@code nextNumber} is read-only (spec §9). */
public record SeriesView(UUID id, UUID storeId, String documentType, String code, long firstNumber,
                         long nextNumber, boolean active, Long version) {
}
