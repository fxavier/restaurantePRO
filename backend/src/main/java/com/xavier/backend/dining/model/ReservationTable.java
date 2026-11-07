package com.xavier.backend.dining.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.UUID;

/**
 * A table a reservation names (informative, D126). It carries the reservation's store so the database refuses a
 * table of another store (composite foreign keys in V4005).
 */
@Embeddable
public record ReservationTable(@Column(name = "table_id", nullable = false) UUID tableId,
                               @Column(name = "store_id", nullable = false) UUID storeId) {
}
