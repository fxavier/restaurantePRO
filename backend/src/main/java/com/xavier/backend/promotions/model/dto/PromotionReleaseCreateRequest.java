package com.xavier.backend.promotions.model.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PromotionReleaseCreateRequest(@NotNull UUID storeId) {
}
