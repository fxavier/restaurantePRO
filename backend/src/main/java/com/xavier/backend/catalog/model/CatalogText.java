package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;

/** Text rules shared by catalog entities: required fields are stripped and never blank. */
final class CatalogText {

    private CatalogText() {
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidCatalogEntryException(field + " is required");
        }
        return value.strip();
    }

    static <T> T required(T value, String field) {
        if (value == null) {
            throw new InvalidCatalogEntryException(field + " is required");
        }
        return value;
    }
}
