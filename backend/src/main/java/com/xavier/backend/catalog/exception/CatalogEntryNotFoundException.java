package com.xavier.backend.catalog.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

/** A catalog entry (family, item, release…) with this id does not exist. 404. */
public class CatalogEntryNotFoundException extends NotFoundException {

    public CatalogEntryNotFoundException(String type, UUID id) {
        super(type + " " + id + " does not exist.");
    }

    private CatalogEntryNotFoundException(String code, String type, UUID id) {
        super(code, type + " " + id + " does not exist.");
    }

    /** Coded 404 {@code STORE_NOT_FOUND} (R5 store exclusions). */
    public static CatalogEntryNotFoundException store(UUID storeId) {
        return new CatalogEntryNotFoundException("STORE_NOT_FOUND", "Store", storeId);
    }

    /** Coded 404 {@code ITEM_NOT_FOUND} (R5 store exclusions). */
    public static CatalogEntryNotFoundException item(UUID itemId) {
        return new CatalogEntryNotFoundException("ITEM_NOT_FOUND", "Item", itemId);
    }
}
