package com.xavier.backend.audit.repository;

import java.util.UUID;

/** Only the store of an audit row, for the D42 (b) check; {@code storeId} is {@code null} for a store-less row. */
public record AuditEventStore(UUID storeId) {
}
