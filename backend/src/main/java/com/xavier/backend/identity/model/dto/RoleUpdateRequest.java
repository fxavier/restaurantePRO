package com.xavier.backend.identity.model.dto;

import com.xavier.backend.access.Permission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** Full replacement; {@code version} is the one the client read (D17). Applies to sessions from their next login. */
public record RoleUpdateRequest(@NotBlank @Size(max = 60) String name, @NotNull Set<Permission> permissions,
                                @NotNull Long version) {
}
