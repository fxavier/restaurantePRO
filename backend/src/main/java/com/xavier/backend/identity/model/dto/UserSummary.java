package com.xavier.backend.identity.model.dto;

import java.util.Set;
import java.util.UUID;

/**
 * A BackOffice account. Never carries the password or its hash.
 *
 * @param support     a support account (D83): it logs in only while it holds an active support grant
 * @param mfaEnrolled the account has an active second factor (D139)
 */
public record UserSummary(UUID id, String username, UUID employeeId, Set<UUID> roleIds, boolean active,
                          boolean mustChangePassword, boolean support, long version, boolean mfaEnrolled) {
}
