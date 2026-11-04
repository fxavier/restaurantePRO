package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

public class UnknownStoreException extends NotFoundException {

    public UnknownStoreException(UUID storeId) {
        super("Store " + storeId + " not found");
    }
}
