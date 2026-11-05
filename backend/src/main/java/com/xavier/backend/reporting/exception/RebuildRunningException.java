package com.xavier.backend.reporting.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.Map;
import java.util.UUID;

/** A rebuild of the store's projections is already running: one at a time per store (D84). 409. */
public class RebuildRunningException extends ConflictException {

    public RebuildRunningException(UUID storeId) {
        super("REBUILD_RUNNING", "A rebuild of store " + storeId + " is already running.", Map.of());
    }
}
