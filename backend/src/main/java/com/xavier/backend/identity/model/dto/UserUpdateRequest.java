package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;

/** Full replacement of an account's editable fields; {@code version} is the one the client read (D17). */
public record UserUpdateRequest(UUID employeeId, Set<UUID> roleIds, @NotNull Boolean active, @NotNull Long version) {

    public UserUpdateRequest {
        roleIds = roleIds == null ? Set.of() : Set.copyOf(roleIds);
    }
}
