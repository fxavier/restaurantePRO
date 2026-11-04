package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

public class DocumentNotFoundException extends NotFoundException {

    public DocumentNotFoundException(UUID id) {
        super("Fiscal document " + id + " not found");
    }
}
