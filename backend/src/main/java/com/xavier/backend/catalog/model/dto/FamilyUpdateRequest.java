package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record FamilyUpdateRequest(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 120) String name,
                                  UUID parentId, @NotNull Long version) {
}
