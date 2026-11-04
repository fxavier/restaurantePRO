package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * A new employee, hired into a first store with a role: whoever has {@code EMPLOYEE_MANAGE} in that store may
 * hire. {@code pin} is 4 to 8 digits (D26).
 */
public record EmployeeCreateRequest(
        @NotBlank @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull @Pattern(regexp = "\\d{4,8}", message = "must be 4 to 8 digits") String pin,
        @NotNull UUID storeId,
        @NotNull UUID roleId) {

    @Override
    public String toString() {
        return "EmployeeCreateRequest[code=" + code + ", name=" + name + ", storeId=" + storeId + ", roleId=" + roleId
                + "]";
    }
}
