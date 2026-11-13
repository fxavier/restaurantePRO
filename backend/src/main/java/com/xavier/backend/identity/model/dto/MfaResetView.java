package com.xavier.backend.identity.model.dto;

import java.time.Instant;

/** The one-time enrolment code an administrator hands over out of band (D143); never stored in clear. */
public record MfaResetView(String enrolmentCode, Instant expiresAt) {

    @Override
    public String toString() {
        return "MfaResetView[expiresAt=" + expiresAt + "]";
    }
}
