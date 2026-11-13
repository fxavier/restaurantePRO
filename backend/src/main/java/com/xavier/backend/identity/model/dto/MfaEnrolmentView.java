package com.xavier.backend.identity.model.dto;

import java.time.Instant;

/**
 * The secret of a pending factor, shown once (D139, D143); the BackOffice draws {@code otpauthUri} as a QR code. This
 * response takes no Idempotency-Key, so it is never stored.
 *
 * @param expiresAt confirm before this, or start again
 */
public record MfaEnrolmentView(String secret, String otpauthUri, Instant expiresAt) {

    @Override
    public String toString() {
        return "MfaEnrolmentView[expiresAt=" + expiresAt + "]";
    }
}
