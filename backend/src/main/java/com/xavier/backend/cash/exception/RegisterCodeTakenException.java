package com.xavier.backend.cash.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** Another register of the store already uses this code. 409. */
public class RegisterCodeTakenException extends ConflictException {

    public RegisterCodeTakenException(String code) {
        super("A cash register with code '" + code + "' already exists in this store.");
    }
}
