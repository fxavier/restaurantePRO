package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.UUID;

/** The store has no active series to number the document with; the draft stays pending. */
public class NoActiveSeriesException extends BusinessRuleException {

    public NoActiveSeriesException(UUID storeId) {
        super("Store " + storeId + " has no active SALE series");
    }
}
