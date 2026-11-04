package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

public class UserNotFoundException extends NotFoundException {

    public UserNotFoundException(UUID id) {
        super("User " + id + " not found.");
    }
}
