package com.xavier.backend.dining.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why the table is locked (shown to the manager who resolves it). */
public record TableLockRequest(@NotBlank @Size(max = 200) String reason) {
}
