package com.xavier.backend.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An incomplete publication for the BackOffice (D88), without its payload (the serialized event may carry business
 * data that the SYSTEM_MONITOR holder has no right to read).
 *
 * @param status             Modulith's own status ({@code PUBLISHED}, {@code PROCESSING}, {@code FAILED},
 *                           {@code RESUBMITTED})
 * @param completionAttempts how many times it was resubmitted (the first delivery is not counted)
 */
public record EventPublicationView(UUID id, String listenerId, String eventType, Instant publicationDate,
                                   Instant lastResubmissionDate, int completionAttempts, String status,
                                   EventPublicationState state) {
}
