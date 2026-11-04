package com.xavier.backend.fiscal.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A requested copy of an issued document. Copies are numbered from 1 per document ({@code copyNo} 1 is the first
 * reprint); the original print has no copy number and no print record.
 */
public record ReprintView(UUID documentId, int copyNo, Instant requestedAt) {
}
