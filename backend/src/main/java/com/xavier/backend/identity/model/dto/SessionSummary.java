package com.xavier.backend.identity.model.dto;

import java.time.Instant;

/** One open session of a user. {@code id} is an opaque handle, never the session id (which is the cookie value). */
public record SessionSummary(String id, Instant createdAt, Instant lastAccessedAt, Instant expiresAt) {
}
