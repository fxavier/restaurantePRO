package com.xavier.backend.cash.model.dto;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A cash session for the POS, with the amount expected per method from its movements. */
public record SessionDetails(UUID id, UUID storeId, UUID registerId, UUID cashDayId, LocalDate businessDate,
                             UUID operatorEmployeeId, String status, Money openingFloat, Instant openedAt,
                             Instant closedAt, long version, List<MethodAmount> expected) {
}
