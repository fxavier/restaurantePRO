package com.xavier.backend.organization.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * One-time enrollment codes and device tokens (D26). Both are high-entropy random values, so a plain SHA-256
 * is enough to store them (no slow password hash needed) and lets the server find the row by hash.
 * Plaintext values are returned to the caller once and never stored or logged.
 */
public final class TerminalSecrets {

    /** Crockford base32: no I, L, O, U, so a code typed by hand is not misread. */
    private static final char[] CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int CODE_LENGTH = 16; // 80 bits
    private static final int TOKEN_BYTES = 32; // 256 bits
    private static final SecureRandom RANDOM = new SecureRandom();

    private TerminalSecrets() {
    }

    static String newEnrollmentCode() {
        char[] code = new char[CODE_LENGTH];
        for (int i = 0; i < code.length; i++) {
            code[i] = CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)];
        }
        return new String(code);
    }

    static String newDeviceToken() {
        byte[] token = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /** Codes are typed by people: case and surrounding spaces do not matter. */
    static String hashEnrollmentCode(String code) {
        return sha256(code.strip().toUpperCase(Locale.ROOT));
    }

    /** Also used by {@code OrganizationApi} to find a terminal by the token a device presents. */
    public static String hashDeviceToken(String token) {
        return sha256(token);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}
