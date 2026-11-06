package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

/**
 * A new BackOffice account. It must change {@code initialPassword} at first login.
 *
 * @param employeeId optional: the employee whose store assignments this account holds
 * @param roleIds    installation-wide roles (all stores); may be empty
 * @param support    a support account (D83, needs {@code SUPPORT_ACCESS_MANAGE}): logs in only during a support
 *                   grant, is never linked to an employee; fixed for the account's life
 */
public record UserCreateRequest(
        @NotBlank @Size(max = 60) @Pattern(regexp = "[A-Za-z0-9._@-]+") String username,
        @NotBlank @Size(max = 200) String initialPassword,
        UUID employeeId,
        Set<UUID> roleIds,
        Boolean support) {

    public UserCreateRequest {
        roleIds = roleIds == null ? Set.of() : Set.copyOf(roleIds);
        support = Boolean.TRUE.equals(support);
    }

    @Override
    public String toString() {
        return "UserCreateRequest[username=" + username + ", employeeId=" + employeeId + ", roleIds=" + roleIds
                + ", support=" + support + "]";
    }
}
