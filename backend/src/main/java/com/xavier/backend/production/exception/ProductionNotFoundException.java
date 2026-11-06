package com.xavier.backend.production.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

/** A printer, ticket or print job that does not exist (404). */
public class ProductionNotFoundException extends NotFoundException {

    public ProductionNotFoundException(String entity, UUID id) {
        super(entity + " " + id + " not found");
    }
}
