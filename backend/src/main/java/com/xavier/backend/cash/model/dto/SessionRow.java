package com.xavier.backend.cash.model.dto;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A cash session in the BackOffice list (Financeiro), with only what is stored: the opening float and, once
 * CLOSED, the operator's blind count per method ({@code counted}, empty while OPEN). {@code closeKind} is
 * {@code OPERATOR} or {@code FORCED} (P22). Read {@code /pos/cash/sessions/{id}} for the expected amounts.
 */
@NullMarked
public record SessionRow(UUID id, UUID storeId, UUID registerId, String registerCode, UUID cashDayId,
                         LocalDate businessDate, UUID operatorEmployeeId, String status, Money openingFloat,
                         Instant openedAt, @Nullable Instant closedAt, @Nullable UUID closedByEmployeeId,
                         @Nullable String closeKind, List<MethodAmount> counted) {
}
