package com.xavier.backend.inventory;

import java.util.UUID;

/** A source that posted stock movements (what {@code StockMovementsPosted} names), for a reporting rebuild. */
public record SourceRef(String sourceType, UUID sourceId) {
}
