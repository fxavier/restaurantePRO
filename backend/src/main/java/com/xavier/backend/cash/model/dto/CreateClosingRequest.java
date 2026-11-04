package com.xavier.backend.cash.model.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateClosingRequest(@NotNull UUID registerId, @NotNull UUID cashDayId) {
}
