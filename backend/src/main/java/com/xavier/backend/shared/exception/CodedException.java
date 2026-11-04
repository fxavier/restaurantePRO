package com.xavier.backend.shared.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The common shape of the shared error types (D22, PRD R2 endpoint conventions): a message that becomes the
 * ProblemDetail {@code detail}, an optional stable {@code code} that clients switch on (never renamed once
 * released), and optional extra ProblemDetail properties (e.g. {@code documentId}, an {@code errors} list). The
 * {@code GlobalExceptionHandler} renders all three. Modules extend {@link NotFoundException},
 * {@link ConflictException} or {@link BusinessRuleException}, never this class.
 */
public abstract sealed class CodedException extends RuntimeException
        permits BusinessRuleException, ConflictException, NotFoundException {

    /** ProblemDetail members and the code itself: a property must not shadow them. */
    private static final Set<String> RESERVED = Set.of("code", "type", "title", "status", "detail", "instance");

    private final String code;
    private final transient Map<String, Object> properties;

    CodedException(String code, String message, Map<String, ?> properties, Throwable cause) {
        super(message, cause);
        if (code != null && code.isBlank()) {
            throw new IllegalArgumentException("an error code must not be blank");
        }
        Objects.requireNonNull(properties, "properties");
        for (String name : properties.keySet()) {
            if (RESERVED.contains(name)) {
                throw new IllegalArgumentException("'" + name + "' is a reserved ProblemDetail property");
            }
        }
        this.code = code;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /** The stable {@code code} property, or {@code null} for an uncoded (R1-style) error. */
    public String code() {
        return code;
    }

    /** Extra ProblemDetail properties, in insertion order; empty when there are none. */
    public Map<String, Object> properties() {
        return properties;
    }
}
