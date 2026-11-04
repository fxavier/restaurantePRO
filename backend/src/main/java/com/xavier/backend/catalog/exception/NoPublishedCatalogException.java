package com.xavier.backend.catalog.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

/** The store has no published catalog yet. 404. */
public class NoPublishedCatalogException extends NotFoundException {

    public NoPublishedCatalogException(UUID storeId) {
        super("Store " + storeId + " has no published catalog.");
    }
}
