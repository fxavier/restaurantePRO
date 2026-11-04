package com.xavier.backend.dining.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The POS table map of a store: its active areas, each with its active tables, ordered by code. */
public record TableMap(UUID storeId, List<Area> areas) {

    public record Area(UUID id, String code, String name, List<Table> tables) {
    }

    /**
     * A table and its indicators. {@code openedAt}, {@code guests} and {@code openedByEmployeeId} describe the open
     * occupation and are {@code null} when the table is FREE. Consumption and balance come from settlement's
     * open-orders read; the POS composes the two (dining cannot see orders).
     */
    public record Table(UUID id, String code, int seats, TableStatus status, boolean locked, boolean subtotalPrinted,
                        Instant openedAt, Integer guests, UUID openedByEmployeeId) {
    }
}
