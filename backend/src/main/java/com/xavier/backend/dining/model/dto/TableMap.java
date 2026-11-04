package com.xavier.backend.dining.model.dto;

import java.util.List;
import java.util.UUID;

/** The POS table map of a store: its active areas, each with its active tables, ordered by code. */
public record TableMap(UUID storeId, List<Area> areas) {

    public record Area(UUID id, String code, String name, List<Table> tables) {
    }

    public record Table(UUID id, String code, int seats, TableStatus status) {
    }
}
