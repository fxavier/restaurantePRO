package com.xavier.backend.cash;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The cash day a session belongs to, for reporting (D85: a payment, refund or adjustment counts in the cash day of
 * its session). {@code status} is the day's: {@code OPEN}, {@code OPERATIONALLY_CLOSED} or {@code FINANCIALLY_CLOSED}.
 */
public record SessionDay(UUID cashDayId, LocalDate businessDate, String status) {
}
