package com.xavier.backend.cash.model.dto;

import com.xavier.backend.cash.model.MovementDirection;
import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * A correction recorded on a closed session (D79 (1)), as the BackOffice session detail lists it: {@code amount} is
 * the positive amount as stored and {@code direction} ({@code IN} or {@code OUT}) says which way it moved the
 * register's expected amount.
 */
@NullMarked
public record AdjustmentRow(UUID id, UUID sessionId, MovementDirection direction, String methodCode, Money amount,
                            String reason, Instant occurredAt) {
}
