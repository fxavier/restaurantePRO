package com.xavier.backend.orders;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A void settlement coordinates under its {@code order_settlement} lock (D71): {@code quantity} of {@code lineId} at
 * the order's {@code expectedVersion}, with its reason, whether the goods were wasted, and the operator.
 */
public record SettledVoidCommand(UUID orderId, long expectedVersion, UUID lineId, BigDecimal quantity, String reason,
                                 boolean wasted, UUID operatorEmployeeId) {

    public SettledVoidCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(lineId, "lineId");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(operatorEmployeeId, "operatorEmployeeId");
    }
}
