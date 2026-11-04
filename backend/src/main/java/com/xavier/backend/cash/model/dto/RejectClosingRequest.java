package com.xavier.backend.cash.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectClosingRequest(@NotBlank @Size(max = 500) String reason) {
}
