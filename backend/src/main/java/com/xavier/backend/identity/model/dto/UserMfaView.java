package com.xavier.backend.identity.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code GET /backoffice/users/{id}/mfa}: an account's second-factor status for an administrator. Never a secret, a
 * code or a hash.
 *
 * @param lockedUntil            set while wrong codes lock its second-factor checks
 * @param enrolmentCodeExpiresAt set while an unused enrolment code from a reset is open
 */
public record UserMfaView(UUID accountId, boolean enrolled, Instant activatedAt, long recoveryCodesLeft,
                          Instant lockedUntil, Instant enrolmentCodeExpiresAt) {
}
