package com.xavier.backend.cash.model.dto;

import java.util.UUID;

/** A cash register as the BackOffice sees it; {@code version} is sent back on update (D17). */
public record RegisterSummary(UUID id, UUID storeId, String code, String name, boolean active, long version) {
}
