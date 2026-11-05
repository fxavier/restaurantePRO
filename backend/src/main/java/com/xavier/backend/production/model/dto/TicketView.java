package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.model.JobKind;
import com.xavier.backend.production.model.TicketSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A production ticket and its printout; {@code suppressed} when its printer was in IGNORE (no job). */
public record TicketView(UUID id, UUID storeId, TicketSource sourceType, UUID sourceId, String destination,
                         JobKind kind, UUID orderId, UUID terminalId, UUID printerId, boolean suppressed,
                         List<String> lines, Instant createdAt) {
}
