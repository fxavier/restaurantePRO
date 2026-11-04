package com.xavier.backend.cash.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record Justification(@NotBlank @Size(max = 30) String methodCode, @NotBlank @Size(max = 500) String text) {
}
