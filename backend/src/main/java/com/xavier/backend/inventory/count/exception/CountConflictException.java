package com.xavier.backend.inventory.count.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/** A count conflicts with the current state: 409 with a stable {@code code}. */
public class CountConflictException extends ConflictException {

    private CountConflictException(String code, String message) {
        super(code, message);
    }

    /** A command the count's status does not allow. */
    public static CountConflictException state(UUID countId, Object status, String action) {
        return new CountConflictException("COUNT_STATE",
                "Count " + countId + " is " + status + " and cannot " + action + ".");
    }

    /** D119: a round confirmed before a cut-off may not be consumed yet, so the expected quantity is not known. */
    public static CountConflictException consumptionBacklog(long pending) {
        return new CountConflictException("CONSUMPTION_BACKLOG", "Stock consumption has " + pending
                + " publication(s) still pending: approve the count once they are processed.");
    }

    public static CountConflictException liveCountExists(UUID warehouseId) {
        return new CountConflictException("LIVE_COUNT_EXISTS",
                "Warehouse " + warehouseId + " already has a count that is not decided or cancelled.");
    }
}
