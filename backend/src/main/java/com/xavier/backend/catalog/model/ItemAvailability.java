package com.xavier.backend.catalog.model;

/** Whether an item can be sold (spec §8.3). Independent from the release life cycle. */
public enum ItemAvailability {
    ACTIVE,
    INACTIVE
}
