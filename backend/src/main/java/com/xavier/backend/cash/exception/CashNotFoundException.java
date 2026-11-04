package com.xavier.backend.cash.exception;

import com.xavier.backend.shared.exception.NotFoundException;

/** The store, register, day, session or closing addressed by the request does not exist. 404. */
public class CashNotFoundException extends NotFoundException {

    public CashNotFoundException(String entityType, Object id) {
        super(entityType + " " + id + " was not found.");
    }
}
