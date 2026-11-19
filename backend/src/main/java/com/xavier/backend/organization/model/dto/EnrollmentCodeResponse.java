package com.xavier.backend.organization.model.dto;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/** The one-time enrollment code, shown once to the admin who asked for it. */
@NullMarked
public record EnrollmentCodeResponse(UUID terminalId, String code, Instant expiresAt) {
}
