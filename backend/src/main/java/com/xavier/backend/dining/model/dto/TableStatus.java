package com.xavier.backend.dining.model.dto;

/**
 * Operational state of a table on the POS map (spec §8.3: a table is FREE when it has no open occupation).
 * R1 has no occupations, so every table is FREE; R2 adds the occupied state with TableOccupation.
 */
public enum TableStatus {
    FREE
}
