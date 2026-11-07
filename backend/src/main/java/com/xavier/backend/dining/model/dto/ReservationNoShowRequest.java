package com.xavier.backend.dining.model.dto;

import jakarta.validation.constraints.NotNull;

public record ReservationNoShowRequest(@NotNull Long version) {
}
