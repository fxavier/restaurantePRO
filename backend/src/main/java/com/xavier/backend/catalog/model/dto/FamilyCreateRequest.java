package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** A new family; with {@code parentId} (a top-level family) it is a subfamily. */
public record FamilyCreateRequest(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 120) String name,
                                  UUID parentId) {
}
