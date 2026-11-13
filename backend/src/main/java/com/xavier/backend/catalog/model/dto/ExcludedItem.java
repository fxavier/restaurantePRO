package com.xavier.backend.catalog.model.dto;

import java.util.UUID;

/** An active item the store's next release leaves out, because the store excludes it (D146). */
public record ExcludedItem(UUID itemId, String code, String name) {
}
