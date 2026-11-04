package com.xavier.backend.dining.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.UUID;

/**
 * A table must sit in a room area of its own store (spec §8.2). An unknown area gets the same answer, so a
 * store manager cannot probe which area ids exist in other stores. 422.
 */
public class AreaNotInStoreException extends BusinessRuleException {

    public AreaNotInStoreException(UUID areaId, UUID storeId) {
        super("Room area " + areaId + " is not an area of store " + storeId + ".");
    }
}
