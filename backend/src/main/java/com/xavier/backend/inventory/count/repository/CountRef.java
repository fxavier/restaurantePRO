package com.xavier.backend.inventory.count.repository;

import com.xavier.backend.inventory.count.model.CountKind;
import java.util.UUID;

/** The store and kind of a count, read alone to authorize first (D42 (b)). */
public record CountRef(UUID storeId, CountKind kind) {
}
