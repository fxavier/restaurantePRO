package com.xavier.backend.cash;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A cash day as settlement and the POS see it. {@code status} is {@code OPEN}, {@code OPERATIONALLY_CLOSED} or
 * {@code FINANCIALLY_CLOSED}; {@code version} is the one {@code closeDayOperationally} expects.
 */
public record DayView(UUID id, UUID storeId, LocalDate businessDate, String status, long version) {
}
