package com.xavier.backend.identity.mfa;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * The random material of second factors (D139, D143) and how codes are stored: a TOTP secret is 160 random bits; a
 * recovery code 10 and an enrolment code 12 base32 characters (5 bits each), shown in groups for reading. Codes are
 * kept only as a keyed hash of their normalized form ({@link SecretCipher#codeHash}, D139 amended): 50 bits fall to
 * an offline search of an unkeyed hash. Normalizing drops separators and spaces and ignores case, so a code typed as
 * shown, or without the dashes, matches. Nothing here logs.
 */
public final class MfaCodes {

    public static final int SECRET_BYTES = 20;
    public static final int RECOVERY_CODE_LENGTH = 10;
    public static final int ENROLMENT_CODE_LENGTH = 12;
    public static final int RECOVERY_CODES_PER_ENROLMENT = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private MfaCodes() {
    }

    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /** E.g. {@code ABCDE-FGHIJ}. */
    public static String newRecoveryCode() {
        return grouped(random(RECOVERY_CODE_LENGTH), 5);
    }

    /** E.g. {@code ABCD-EFGH-IJKL}. */
    public static String newEnrolmentCode() {
        return grouped(random(ENROLMENT_CODE_LENGTH), 4);
    }

    /** The code without separators or spaces, upper case: what is hashed ({@link SecretCipher#codeHash}). */
    public static String normalize(String code) {
        return code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    /**
     * The key URI authenticator apps read (QR code drawn by the BackOffice):
     * {@code otpauth://totp/<issuer>:<username>?secret=…&issuer=…&digits=6&period=30}.
     */
    public static String otpauthUri(String issuer, String username, String base32Secret) {
        String label = encode(issuer) + ":" + encode(username);
        return "otpauth://totp/" + label + "?secret=" + base32Secret.replace("=", "") + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.PERIOD;
    }

    private static String random(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(Base32.ALPHABET.charAt(RANDOM.nextInt(Base32.ALPHABET.length())));
        }
        return code.toString();
    }

    private static String grouped(String code, int size) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < code.length(); i += size) {
            if (i > 0) {
                text.append('-');
            }
            text.append(code, i, Math.min(code.length(), i + size));
        }
        return text.toString();
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
