package com.xavier.backend.catalog.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

/** A catalog entry (family, item, release…) with this id does not exist. 404. */
public class CatalogEntryNotFoundException extends NotFoundException {

    public CatalogEntryNotFoundException(String type, UUID id) {
        super(type + " " + id + " does not exist.");
    }
}
