package com.xavier.backend.reporting.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

/** The store a report or rebuild names does not exist. 404. */
public class ReportStoreNotFoundException extends NotFoundException {

    public ReportStoreNotFoundException(UUID storeId) {
        super("Store " + storeId + " was not found.");
    }
}
