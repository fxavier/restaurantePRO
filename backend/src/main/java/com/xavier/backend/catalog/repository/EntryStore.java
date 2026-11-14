package com.xavier.backend.catalog.repository;

import java.util.UUID;

/**
 * Only the store of a store-or-company-wide entry, read before authorizing (D42); {@code storeId} is {@code null} for
 * an entry of every store. A record built by a constructor expression, so a {@code null} store is still a present row
 * (a single-property interface projection of {@code null} reads as no row).
 */
public record EntryStore(UUID storeId) {
}
