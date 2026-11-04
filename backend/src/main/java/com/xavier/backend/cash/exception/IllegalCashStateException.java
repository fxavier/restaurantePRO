package com.xavier.backend.cash.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** A command does not apply in the aggregate's current state (spec §8.3 state machines, D §4). 409. */
public class IllegalCashStateException extends ConflictException {

    public IllegalCashStateException(String entityType, Object id, Enum<?> state, String command) {
        super(entityType + " " + (id == null ? "" : id + " ") + "is " + state + ": cannot " + command + ".");
    }
}
