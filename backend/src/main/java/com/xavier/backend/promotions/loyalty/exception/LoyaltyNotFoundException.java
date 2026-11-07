package com.xavier.backend.promotions.loyalty.exception;

import com.xavier.backend.shared.exception.NotFoundException;

/** The program version, reward or customer addressed by the request does not exist. 404. */
public class LoyaltyNotFoundException extends NotFoundException {

    public LoyaltyNotFoundException(String entityType, Object id) {
        super(entityType + " " + id + " was not found.");
    }
}
