package com.xavier.backend.cash;

import java.util.UUID;

/** The OPEN session a payment collected by {@code operatorEmployeeId} goes to (P16). */
public record OpenSessionView(UUID id, UUID storeId, UUID registerId, UUID cashDayId, UUID operatorEmployeeId) {
}
