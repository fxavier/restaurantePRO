package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.model.JobKind;
import com.xavier.backend.production.model.PrintJobStatus;
import com.xavier.backend.production.model.PrintResolution;
import com.xavier.backend.production.model.TicketSource;
import java.time.Instant;
import java.util.UUID;

/**
 * One delivery attempt and what it prints: the ticket's source and destination, and the order it belongs to (when
 * it does). {@code resolution} tells how a FAILED or UNKNOWN job was dealt with.
 */
public record PrintJobView(UUID id, UUID storeId, UUID ticketId, UUID printerId, JobKind kind, PrintJobStatus status,
                           int attempts, Instant nextAttemptAt, String lastError, UUID retryOf,
                           PrintResolution resolution, Instant createdAt, Instant sentAt, TicketSource sourceType,
                           UUID sourceId, String destination, UUID orderId) {
}
