package com.xavier.backend.access;

import java.util.UUID;

/**
 * Where a POS command runs, taken from the session only (D39): POS write endpoints never read the store or
 * terminal from the request. Returned by {@link AccessPolicy#requirePos}.
 */
public record PosContext(UUID employeeId, UUID terminalId, UUID storeId) {
}
