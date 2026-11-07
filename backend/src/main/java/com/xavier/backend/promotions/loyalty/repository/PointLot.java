package com.xavier.backend.promotions.loyalty.repository;

import java.util.UUID;

/** A point lot (an entry with points &gt; 0) and the points it still has. */
public interface PointLot {

    UUID getEntryId();

    int getRemaining();
}
