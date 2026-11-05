package com.xavier.backend.settlement.repository;

import java.util.UUID;

/**
 * Where an attempt belongs, read alone before authorizing and locking (D42, D51, D76): its order, or for a debt
 * payment its customer's credit account ({@code orderId} null).
 */
public record AttemptRef(UUID storeId, UUID orderId, UUID creditAccountId) {
}
