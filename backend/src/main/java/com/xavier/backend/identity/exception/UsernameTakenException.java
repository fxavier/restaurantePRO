package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.ConflictException;

public class UsernameTakenException extends ConflictException {

    public UsernameTakenException(String username) {
        super("Username " + username + " is already taken.");
    }
}
