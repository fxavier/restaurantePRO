package com.xavier.backend.promotions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Earn the points of a closing order (D111): the order, where and on which business day it closed, its customer, the
 * close instant (which picks the program version in force) and its billing lines.
 */
public record EarnCommand(UUID orderId, UUID storeId, UUID customerId, LocalDate businessDate, Instant at,
                          List<EarnLine> lines) {

    public EarnCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(at, "at");
        lines = List.copyOf(lines);
    }
}
