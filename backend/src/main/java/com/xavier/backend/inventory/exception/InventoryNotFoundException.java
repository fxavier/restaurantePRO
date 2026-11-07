package com.xavier.backend.inventory.exception;

import com.xavier.backend.shared.exception.NotFoundException;

/** The stock item, pack, warehouse, route, level or transfer addressed by the request does not exist. 404. */
public class InventoryNotFoundException extends NotFoundException {

    public InventoryNotFoundException(String entityType, Object id) {
        super(entityType + " " + id + " was not found.");
    }
}
