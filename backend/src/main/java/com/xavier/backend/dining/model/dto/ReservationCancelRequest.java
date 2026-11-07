package com.xavier.backend.dining.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReservationCancelRequest(@NotNull Long version, @NotBlank @Size(max = 500) String reason) {
}
