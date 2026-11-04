package com.xavier.backend.fiscal.model.dto;

import java.time.Instant;
import java.util.UUID;

/** A requested copy: the document and its copy number (1 for the first reprint). */
public record ReprintView(UUID documentId, int copyNo, Instant requestedAt) {
}
