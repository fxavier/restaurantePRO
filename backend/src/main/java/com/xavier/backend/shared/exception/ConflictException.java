package com.xavier.backend.shared.exception;

import java.util.Map;

/**
 * The request conflicts with the current state: an illegal state transition, a duplicate, an operation
 * already in progress. Mapped to 409 (a stale {@code @Version} also maps to 409, code {@code VERSION_CONFLICT}),
 * always with a stable {@code code} property, so a client can tell the conflicts apart (FE-F29), and any extra
 * properties. Extend per module.
 */
public non-sealed class ConflictException extends CodedException {

    public ConflictException(String code, String message) {
        super(requireCode(code), message, Map.of(), null);
    }

    public ConflictException(String code, String message, Map<String, ?> properties) {
        super(requireCode(code), message, properties, null);
    }

    public ConflictException(String code, String message, Map<String, ?> properties, Throwable cause) {
        super(requireCode(code), message, properties, cause);
    }

    private static String requireCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("a conflict needs a code (FE-F29)");
        }
        return code;
    }
}
