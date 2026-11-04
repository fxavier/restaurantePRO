package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** A sale draft whose lines, sums or currency are inconsistent (D59). 422. */
public class InvalidSaleDraftException extends BusinessRuleException {

    public InvalidSaleDraftException(String message) {
        super(message);
    }
}
