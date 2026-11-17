package com.xavier.backend.organization.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * A device's one-time enrolment code (D182): {@value #SELECTOR_LENGTH} characters of public selector, then
 * {@value #SECRET_LENGTH} of secret (80 bits), in Crockford base32 (no I, L, O, U, so a code typed by hand is not
 * misread). The selector finds the code (and its tenant) before anyone is authenticated; only the secret's SHA-256 is
 * stored, and five wrong secrets for one selector burn the code. The plaintext is shown once and never logged.
 */
public record EnrolmentCode(String selector, String secret) {

    static final int SELECTOR_LENGTH = 8;
    static final int SECRET_LENGTH = 16;
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static EnrolmentCode generate() {
        return new EnrolmentCode(random(SELECTOR_LENGTH), random(SECRET_LENGTH));
    }

    /** A typed code: case, spaces and dashes do not matter; anything else that is not a code is empty. */
    public static Optional<EnrolmentCode> parse(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String code = typed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (code.length() != SELECTOR_LENGTH + SECRET_LENGTH || !code.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0)) {
            return Optional.empty();
        }
        return Optional.of(new EnrolmentCode(code.substring(0, SELECTOR_LENGTH), code.substring(SELECTOR_LENGTH)));
    }

    /** What the administrator types on the device. */
    public String text() {
        return selector + secret;
    }

    /** The only form of the secret that is stored. */
    public String secretHash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    /** Never the secret. */
    @Override
    public String toString() {
        return "EnrolmentCode[selector=" + selector + "]";
    }

    private static String random(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));
        }
        return new String(chars);
    }
}
