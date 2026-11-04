package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Full replacement of an employee's editable fields; {@code version} is the one the client read (D17). */
public record EmployeeUpdateRequest(@NotBlank @Size(max = 120) String name, @NotNull Boolean active,
                                    @NotNull Long version) {
}
