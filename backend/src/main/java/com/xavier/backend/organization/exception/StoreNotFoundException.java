package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

public class StoreNotFoundException extends NotFoundException {

    public StoreNotFoundException(UUID id) {
        super("Store " + id + " does not exist.");
    }
}
