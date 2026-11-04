package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

public class SeriesNotFoundException extends NotFoundException {

    public SeriesNotFoundException(UUID id) {
        super("Fiscal series " + id + " not found");
    }
}
