package com.xavier.backend.inventory.repository;

import java.util.UUID;

/** The two stores of a transfer, read alone to authorize first (D42 (b)). */
public record TransferStores(UUID fromStoreId, UUID toStoreId) {
}
