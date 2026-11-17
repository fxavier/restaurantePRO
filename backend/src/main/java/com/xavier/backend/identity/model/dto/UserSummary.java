package com.xavier.backend.identity.model.dto;

import java.util.Set;
import java.util.UUID;

/**
 * A BackOffice account (its credentials and second factor live in Keycloak, D175).
 *
 * @param support a support account (D83): it acts only while it holds an active support grant
 */
public record UserSummary(UUID id, String username, UUID employeeId, Set<UUID> roleIds, boolean active,
                          boolean support, long version) {
}
