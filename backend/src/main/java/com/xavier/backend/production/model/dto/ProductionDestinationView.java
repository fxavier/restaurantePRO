package com.xavier.backend.production.model.dto;

/**
 * A production destination of a store (a station of the kitchen display): the code catalog items name (KITCHEN,
 * BAR…). The code is all production records of a destination; it has no name of its own.
 */
public record ProductionDestinationView(String code) {
}
