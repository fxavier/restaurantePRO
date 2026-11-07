package com.xavier.backend.inventory.purchasing.model;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * How much of a purchase order (or one of its lines) has arrived, derived from its confirmed receipts and never
 * stored (D124): the lifecycle state ({@link PurchaseOrderStatus}) and the receipt state stay separate.
 */
public enum ReceiptProgress {
    NONE, PARTIAL, FULL;

    /** A line's progress: nothing received, some, or at least what was ordered (over-receipt is FULL). */
    public static ReceiptProgress of(BigDecimal ordered, BigDecimal received) {
        if (received.signum() <= 0) {
            return NONE;
        }
        return received.compareTo(ordered) >= 0 ? FULL : PARTIAL;
    }

    /** An order's progress: NONE when no line received anything, FULL when every line is FULL, else PARTIAL. */
    public static ReceiptProgress ofOrder(Collection<ReceiptProgress> lines) {
        if (lines.stream().allMatch(line -> line == NONE)) {
            return NONE;
        }
        return lines.stream().allMatch(line -> line == FULL) ? FULL : PARTIAL;
    }
}
