package com.xavier.backend.identity.mfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP as authenticator apps use it (D139): HMAC-SHA1, 30-second steps from the epoch, 6 digits. Pure:
 * the caller passes the step (from its {@code Clock}), and replay protection is the caller's {@code lastUsedStep}.
 */
public final class Totp {

    /** Seconds per step. */
    public static final long PERIOD = 30;
    public static final int DIGITS = 6;
    /** Accepted clock drift, in steps either side of now. */
    public static final int WINDOW = 1;
    private static final int MODULUS = 1_000_000;

    private Totp() {
    }

    public static long stepAt(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), PERIOD);
    }

    /** The code of {@code step} (RFC 4226 dynamic truncation), as a number below 10^6. */
    public static int codeAt(byte[] secret, long step) {
        byte[] hash = hmacSha1(secret, ByteBuffer.allocate(Long.BYTES).putLong(step).array());
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
        return binary % MODULUS;
    }

    /** Whether {@code code} is the code of exactly {@code step}; compared in constant time. */
    public static boolean verify(byte[] secret, int code, long step) {
        byte[] expected = Integer.toString(codeAt(secret, step)).getBytes(StandardCharsets.US_ASCII);
        byte[] given = Integer.toString(code).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given);
    }

    /**
     * The step within {@link #WINDOW} of {@code currentStep} whose code is {@code code}, later than
     * {@code lastUsedStep} (a code is accepted once, and never one of an earlier step); empty when none.
     */
    public static OptionalLong matchingStep(byte[] secret, int code, long currentStep, long lastUsedStep) {
        for (long step = currentStep - WINDOW; step <= currentStep + WINDOW; step++) {
            if (step > lastUsedStep && verify(secret, code, step)) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    private static byte[] hmacSha1(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is not available", e);
        }
    }
}
