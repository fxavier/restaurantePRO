package com.xavier.backend.cash.model.dto;

import java.util.List;
import java.util.UUID;

/**
 * A cash closing; {@code dayRound} is the day's round (its financial reopens when drafted), {@code supersedesId} the
 * REJECTED attempt of the same round it replaces (null for the round's first attempt).
 */
public record ClosingView(UUID id, UUID storeId, UUID registerId, UUID cashDayId, int dayRound, int attemptNo,
                          UUID supersedesId, String status, long version, String rejectionReason,
                          List<ClosingLineView> lines) {
}
