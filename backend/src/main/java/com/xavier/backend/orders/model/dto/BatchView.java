package com.xavier.backend.orders.model.dto;

import com.xavier.backend.orders.model.BatchStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A confirmed round; {@code orderVersion} is the order's version after it, for the next command. */
public record BatchView(UUID id, UUID orderId, int batchNo, BatchStatus status, Instant confirmedAt,
                        UUID confirmedByEmployeeId, long orderVersion, List<LineView> lines) {
}
