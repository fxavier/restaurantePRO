package com.xavier.backend.dining.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * What orders needs to know about a table (S13): its store, whether it and its area are active, whether it is
 * locked (a locked table refuses new lines, rounds and incoming transfers), and the order of its open occupation
 * ({@code null} when the table is FREE). {@code reserved} and {@code nextReservationAt} are the D126 map indicator:
 * informative, they never block anything.
 */
public record TableState(UUID tableId, UUID storeId, boolean active, boolean areaActive, boolean locked,
                         UUID openOrderId, boolean reserved, Instant nextReservationAt) {

    /** The R2 shape, without a reservation. */
    public TableState(UUID tableId, UUID storeId, boolean active, boolean areaActive, boolean locked,
                      UUID openOrderId) {
        this(tableId, storeId, active, areaActive, locked, openOrderId, false, null);
    }
}
