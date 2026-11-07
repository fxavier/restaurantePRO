package com.xavier.backend.dining.repository;

import java.time.Instant;
import java.util.UUID;

/** The earliest indicated BOOKED reservation of a table (D126 map indicator). */
public record NextReservation(UUID tableId, Instant startsAt) {
}
