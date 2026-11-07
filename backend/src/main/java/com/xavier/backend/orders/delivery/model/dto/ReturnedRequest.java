package com.xavier.backend.orders.delivery.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReturnedRequest(@NotNull Long version, @NotBlank @Size(max = 300) String reason) {
}
