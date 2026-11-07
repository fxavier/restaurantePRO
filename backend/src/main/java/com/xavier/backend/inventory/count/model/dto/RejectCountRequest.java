package com.xavier.backend.inventory.count.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectCountRequest(@NotBlank @Size(max = 500) String reason) {
}
