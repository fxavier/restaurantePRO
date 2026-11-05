package com.xavier.backend.settlement.repository;

import java.util.UUID;

/** Where a partition belongs, read alone before authorizing and locking (D42, D51). */
public record PartitionRef(UUID storeId, UUID orderId) {
}
