package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** 409 with code {@value #CODE}. */
public class RoleNameTakenException extends ConflictException {

    public static final String CODE = "ROLE_NAME_TAKEN";

    public RoleNameTakenException(String name) {
        super(CODE, "Role name " + name + " is already taken.");
    }
}
