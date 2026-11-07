package com.xavier.backend.orders;

import java.util.UUID;

/** A discount source (D100): {@code BATCH} (a round with a promoted line) or {@code ADJUSTMENT}, and its id. */
public record SourceRef(String sourceType, UUID sourceId) {
}
