package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.NotFoundException;

public class SessionNotFoundException extends NotFoundException {

    public SessionNotFoundException() {
        super("Session not found.");
    }
}
