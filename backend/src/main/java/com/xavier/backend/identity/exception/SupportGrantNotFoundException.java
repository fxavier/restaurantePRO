package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

public class SupportGrantNotFoundException extends NotFoundException {

    public SupportGrantNotFoundException(UUID id) {
        super("Support grant " + id + " not found.");
    }
}
