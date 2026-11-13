package com.xavier.backend.identity.model.dto;

import com.xavier.backend.identity.mfa.MfaMode;
import java.time.Instant;

/**
 * {@code GET /auth/mfa}: the installation's mode and the caller's factor. Never a secret.
 *
 * @param stepUpValidUntil until when this session needs no new code for a sensitive permission; {@code null} when
 *                         it never proved one
 */
public record MfaStatusView(MfaMode mode, boolean enrolled, Instant activatedAt, long recoveryCodesLeft,
                            Instant stepUpValidUntil) {
}
