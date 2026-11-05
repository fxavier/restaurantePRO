package com.xavier.backend.shared.exception;

import java.util.Map;

/**
 * A system the request depends on did not answer in time (an external provider): nothing was changed, and the same
 * request may be retried later. Mapped to 503 with the stable {@code code} property. Extend per module.
 */
public non-sealed class UnavailableException extends CodedException {

    public UnavailableException(String code, String message, Map<String, ?> properties) {
        super(code, message, properties, null);
    }
}
