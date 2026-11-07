package com.xavier.backend.inventory.model.dto;

import java.time.Instant;
import java.util.UUID;

public record ActivationView(UUID storeId, Instant activeFrom, UUID activatedByAccountId, Instant activatedAt) {
}
