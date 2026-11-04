package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.ConflictException;

public class RoleNameTakenException extends ConflictException {

    public RoleNameTakenException(String name) {
        super("Role name " + name + " is already taken.");
    }
}
