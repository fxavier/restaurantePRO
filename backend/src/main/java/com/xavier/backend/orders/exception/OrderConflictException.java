package com.xavier.backend.orders.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.Map;
import java.util.UUID;

/**
 * The request conflicts with the order's current state (409): the order is no longer OPEN ({@code ORDER_NOT_OPEN}),
 * a line is not a DRAFT any more ({@code LINE_NOT_DRAFT}) or
 * not confirmed yet for a void or transfer ({@code LINE_NOT_CONFIRMED}), the POS priced its drafts from an older catalog than the
 * published one ({@code CATALOG_CHANGED}, spec §8.4: the POS re-reads and shows the new prices), or the table already
 * has an OPEN order ({@code TABLE_OCCUPIED}). A stale {@code version}/{@code orderVersion} is a plain 409 as well.
 */
public class OrderConflictException extends ConflictException {

    private OrderConflictException(String code, String detail) {
        super(code, detail);
    }

    private OrderConflictException(String code, String detail, Map<String, ?> properties) {
        super(code, detail, properties);
    }

    public static OrderConflictException notOpen(UUID orderId) {
        return new OrderConflictException("ORDER_NOT_OPEN", "Order " + orderId + " is not open.");
    }

    public static OrderConflictException lineNotDraft(UUID lineId) {
        return new OrderConflictException("LINE_NOT_DRAFT",
                "Line " + lineId + " is not a draft line of this order.");
    }

    public static OrderConflictException lineNotConfirmed(UUID lineId) {
        return new OrderConflictException("LINE_NOT_CONFIRMED",
                "Line " + lineId + " is not a confirmed line of this order.");
    }

    public static OrderConflictException catalogChanged(long sent, long current) {
        return new OrderConflictException("CATALOG_CHANGED",
                "The catalog changed (version " + sent + " → " + current + "): review the prices and confirm again.",
                Map.of("catalogVersion", current));
    }

    public static OrderConflictException tableOccupied(UUID tableId) {
        return new OrderConflictException("TABLE_OCCUPIED", "Table " + tableId + " is occupied.");
    }
}
