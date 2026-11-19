package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.ConflictException;

/**
 * The store already has a series with that code ({@code SERIES_CODE_TAKEN}: choose another code), or already has an
 * active series of that type ({@code ACTIVE_SERIES_EXISTS}: deactivate it first). 409.
 */
public class SeriesTakenException extends ConflictException {

    private SeriesTakenException(String code, String message) {
        super(code, message);
    }

    public static SeriesTakenException codeTaken(String seriesCode) {
        return new SeriesTakenException("SERIES_CODE_TAKEN", "The store already has a series " + seriesCode);
    }

    public static SeriesTakenException activeExists() {
        return new SeriesTakenException("ACTIVE_SERIES_EXISTS",
                "The store already has an active SALE series; deactivate it first");
    }
}
