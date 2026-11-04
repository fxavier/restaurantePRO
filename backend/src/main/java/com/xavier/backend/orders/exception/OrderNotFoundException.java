package com.xavier.backend.orders.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

/** The order, line or round addressed by the request does not exist (in that order). 404. */
public class OrderNotFoundException extends NotFoundException {

    public OrderNotFoundException(String entityType, UUID id) {
        super(entityType + " " + id + " was not found.");
    }
}
