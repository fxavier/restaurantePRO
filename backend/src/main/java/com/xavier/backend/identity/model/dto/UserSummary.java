package com.xavier.backend.identity.model.dto;

import java.util.Set;
import java.util.UUID;

/** A BackOffice account. Never carries the password or its hash. */
public record UserSummary(UUID id, String username, UUID employeeId, Set<UUID> roleIds, boolean active,
                          boolean mustChangePassword, long version) {
}
