package com.xavier.backend.dining.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * What staff book or edit on a reservation (D126). The tables are informative: any table of the store may seat it.
 *
 * @param contactPhoneDigits digits only, or {@code null}
 */
public record ReservationDetails(Instant startsAt, Integer durationMinutes, int partySize, String contactName,
                                 String contactPhoneDigits, UUID customerId, String notes, Set<UUID> tableIds) {
}
