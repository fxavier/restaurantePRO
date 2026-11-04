package com.xavier.backend.identity.service;

import com.xavier.backend.identity.exception.WeakPasswordException;
import java.nio.charset.StandardCharsets;

/**
 * The strong-password rule for BackOffice accounts (spec §10, D26): at least {@value #MIN_LENGTH} characters.
 * The upper bound is BCrypt's: it only reads the first {@value #MAX_BYTES} bytes, and Spring's encoder refuses
 * longer input.
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    static void requireStrong(String password) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
            throw new WeakPasswordException("A password needs at least " + MIN_LENGTH + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new WeakPasswordException("A password can have at most " + MAX_BYTES + " bytes.");
        }
    }
}
