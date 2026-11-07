package com.xavier.backend.orders;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A price adjustment settlement coordinates under its {@code order_settlement} lock (D105): {@code kind} on
 * {@code lines} of the order at {@code expectedVersion}, with a {@code percent} or {@code unitPrice} (DISCOUNT), a
 * {@code rewardCode} (REWARD), its reason and operator. REVERSAL raises the bill and is refused on this route.
 */
public record SettledAdjustmentCommand(UUID orderId, long expectedVersion, List<AdjustLine> lines,
                                       AdjustmentKind kind, BigDecimal percent, Money unitPrice, String rewardCode,
                                       String reason, UUID operatorEmployeeId) {

    public SettledAdjustmentCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(operatorEmployeeId, "operatorEmployeeId");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("An adjustment names at least one line");
        }
    }
}
