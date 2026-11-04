package com.xavier.backend.identity.model.dto;

import com.xavier.backend.access.Permission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

public record RoleCreateRequest(@NotBlank @Size(max = 60) String name, @NotNull Set<Permission> permissions) {
}
