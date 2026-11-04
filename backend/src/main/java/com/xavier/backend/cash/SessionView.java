package com.xavier.backend.cash;

import java.util.UUID;

/**
 * A cash session as settlement sees it. {@code status} is {@code OPEN} or {@code CLOSED}; {@code version} is the one
 * {@code closeSession} expects.
 */
public record SessionView(UUID id, UUID storeId, UUID registerId, UUID cashDayId, UUID operatorEmployeeId,
                          String status, long version) {
}
