package com.xavier.backend.dining.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** The POS table map of a store: its active areas, each with its active tables, ordered by code. */
@NullMarked
public record TableMap(UUID storeId, List<Area> areas) {

    public record Area(UUID id, String code, String name, List<Table> tables) {
    }

    /**
     * A table and its indicators. {@code openedAt}, {@code guests}, {@code openedByEmployeeId} and
     * {@code openOrderId} describe the open occupation and are {@code null} when the table is FREE;
     * {@code openOrderId} is the order the occupation was opened for (§8.1, referenced by id, D6), so the POS opens
     * it directly. The order's total and last round come from orders' table-activity read, and its balance from
     * settlement's open-orders read; the POS composes them by order id (dining holds the order's id only, never its
     * content: orders depends on dining, never the reverse). {@code reserved} and
     * {@code nextReservationAt} show a BOOKED reservation within the store's window (D126, P47), independent of the
     * other indicators.
     */
    public record Table(UUID id, String code, int seats, TableStatus status, boolean locked, boolean subtotalPrinted,
                        @Nullable Instant openedAt, @Nullable Integer guests, @Nullable UUID openedByEmployeeId,
                        @Nullable UUID openOrderId, boolean reserved, @Nullable Instant nextReservationAt) {
    }
}
