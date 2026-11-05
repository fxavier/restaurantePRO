package com.xavier.backend.settlement.repository;

import java.util.UUID;

/** Where an attempt belongs, read alone before authorizing and locking (D42, D51). */
public record AttemptRef(UUID storeId, UUID orderId) {
}
