package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** 409 with code {@value #CODE}. */
public class UsernameTakenException extends ConflictException {

    public static final String CODE = "USERNAME_TAKEN";

    public UsernameTakenException(String username) {
        super(CODE, "Username " + username + " is already taken.");
    }
}
