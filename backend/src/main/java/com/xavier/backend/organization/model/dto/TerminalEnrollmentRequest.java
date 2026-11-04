package com.xavier.backend.organization.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TerminalEnrollmentRequest(@NotBlank @Size(max = 64) String code) {
}
