package com.xavier.backend.orders.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A recorded transfer and both orders after it. */
public record TransferView(UUID id, UUID sourceOrderId, UUID targetOrderId, Instant transferredAt,
                           List<Line> lines, OrderView source, OrderView target) {

    /** {@code quantity} left {@code sourceLineId} and is billed on {@code targetLineId}. */
    public record Line(UUID sourceLineId, UUID targetLineId, BigDecimal quantity) {
    }
}
