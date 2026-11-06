package com.xavier.backend.audit.model.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.util.UUID;

/**
 * One audit row as read by an auditor (spec §10). {@code details} is the stored JSON object, written as is: the
 * secret-key guard ran when it was recorded (D82).
 */
public record AuditEventView(UUID id, Instant occurredAt, String actorKind, UUID actorId, UUID approverId,
                             String action, String entityType, String entityId, UUID storeId, UUID terminalId,
                             String reason, @JsonRawValue String details) {
}
