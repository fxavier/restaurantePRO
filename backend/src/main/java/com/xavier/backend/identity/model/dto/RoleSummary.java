package com.xavier.backend.identity.model.dto;

import com.xavier.backend.access.Permission;
import java.util.Set;
import java.util.UUID;

public record RoleSummary(UUID id, String name, Set<Permission> permissions, long version) {
}
