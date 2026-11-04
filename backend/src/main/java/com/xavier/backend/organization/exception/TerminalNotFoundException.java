package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

public class TerminalNotFoundException extends NotFoundException {

    public TerminalNotFoundException(UUID id) {
        super("Terminal " + id + " does not exist.");
    }
}
