package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AssignmentRequest(@NotNull UUID storeId, @NotNull UUID roleId) {
}
