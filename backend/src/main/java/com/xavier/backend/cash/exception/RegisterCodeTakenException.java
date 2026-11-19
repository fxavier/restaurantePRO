package com.xavier.backend.cash.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** Another register of the store already uses this code. 409 with code {@value #CODE}. */
public class RegisterCodeTakenException extends ConflictException {

    public static final String CODE = "REGISTER_CODE_TAKEN";

    public RegisterCodeTakenException(String code) {
        super(CODE, "A cash register with code '" + code + "' already exists in this store.");
    }
}
