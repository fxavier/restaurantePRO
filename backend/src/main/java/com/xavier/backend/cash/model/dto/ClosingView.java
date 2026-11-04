package com.xavier.backend.cash.model.dto;

import java.util.List;
import java.util.UUID;

/** A cash closing; {@code supersedesId} is the REJECTED attempt it replaces (null for the first attempt). */
public record ClosingView(UUID id, UUID storeId, UUID registerId, UUID cashDayId, int attemptNo, UUID supersedesId,
                          String status, long version, String rejectionReason, List<ClosingLineView> lines) {
}
