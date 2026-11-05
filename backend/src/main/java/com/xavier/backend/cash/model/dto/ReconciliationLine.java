package com.xavier.backend.cash.model.dto;

import com.xavier.backend.shared.Money;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One cash day × register × method of the cash reconciliation (D79 (7)). {@code expected} is what the movements say
 * now, adjustments included, and {@code adjustments} the part adjustments moved; {@code difference} is
 * {@code counted − expected}. The closing fields are the latest closing of the register for the day (latest round,
 * then latest attempt), null when none was drafted; {@code justification} is its line's for the method.
 */
public record ReconciliationLine(UUID cashDayId, LocalDate businessDate, String dayStatus, UUID registerId,
                                 String methodCode, Money expected, Money counted, Money adjustments,
                                 Money difference, String justification, UUID closingId, String closingStatus,
                                 Integer dayRound) {
}
