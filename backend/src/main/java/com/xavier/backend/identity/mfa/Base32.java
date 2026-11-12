package com.xavier.backend.identity.mfa;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** Base32 (RFC 4648 §6): the alphabet a TOTP secret is shown in (D139). Our own few lines; no dependency. */
public final class Base32 {

    static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    /** Upper case, padded with {@code =} to a multiple of 8 characters, as RFC 4648 writes it. */
    public static String encode(byte[] data) {
        StringBuilder text = new StringBuilder((data.length + 4) / 5 * 8);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                text.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            text.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        while (text.length() % 8 != 0) {
            text.append('=');
        }
        return text.toString();
    }

    /**
     * Any case, padding optional.
     *
     * @throws IllegalArgumentException for a character outside the alphabet
     */
    public static byte[] decode(String text) {
        String normalized = text.toUpperCase(Locale.ROOT).replace("=", "");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(normalized.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < normalized.length(); i++) {
            int value = ALPHABET.indexOf(normalized.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("not a base32 character at position " + i);
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bytes.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return bytes.toByteArray();
    }
}
