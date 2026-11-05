package com.xavier.backend.customers.exception;

import com.xavier.backend.shared.exception.NotFoundException;

/** The customer, contact, address or ledger entry addressed by the request does not exist. 404. */
public class CustomersNotFoundException extends NotFoundException {

    public CustomersNotFoundException(String entityType, Object id) {
        super(entityType + " " + id + " was not found.");
    }
}
