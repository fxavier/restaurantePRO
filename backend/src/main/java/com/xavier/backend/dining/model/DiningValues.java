package com.xavier.backend.dining.model;

import com.xavier.backend.dining.exception.InvalidDiningSetupException;

final class DiningValues {

    private DiningValues() {
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidDiningSetupException(field + " is required");
        }
        return value.strip();
    }
}
