package com.xavier.backend.identity.mfa;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Seals TOTP secrets at rest (D139): AES-256-GCM, a fresh 96-bit nonce per secret, the account id as associated data
 * (a sealed secret copied onto another account does not open), and the key from {@code mfa.key-file}. A database dump
 * alone then cannot generate codes. The installation does not start when MFA is on and the key is missing or not 32
 * bytes, nor when a key file is configured but unreadable. The same key, through per-purpose sub-keys, keys the hashes
 * of recovery and enrolment codes ({@link #codeHash}). Never logs a secret or the key.
 */
@Component
public class SecretCipher {

    /** A sealed secret as stored on the factor; {@code keyId} names the key for a later rotation. */
    public record Sealed(byte[] ciphertext, byte[] nonce, String keyId) {
    }

    /**
     * What a code is for. Each purpose hashes with its own sub-key, {@code HMAC-SHA256(key, label)}, so a recovery
     * code's hash never matches as an enrolment code's and the AES key itself is never used as a MAC key.
     */
    public enum CodePurpose {
        RECOVERY_CODE("restaurantpro/mfa/recovery-code/v1"),
        ENROLMENT_CODE("restaurantpro/mfa/enrolment-code/v1");

        private final String label;

        CodePurpose(String label) {
            this.label = label;
        }
    }

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String HMAC = "HmacSHA256";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String PROPERTY = "restaurantpro.security.mfa.key-file";

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;
    private final String keyId;
    private final Map<CodePurpose, SecretKeySpec> codeKeys = new EnumMap<>(CodePurpose.class);

    public SecretCipher(MfaProperties properties) {
        Path file = properties.keyFile();
        if (file == null) {
            if (properties.mode() != MfaMode.OFF) {
                throw new IllegalStateException("MFA mode " + properties.mode() + " needs " + PROPERTY
                        + " (a secret file with 32 random bytes in base64)");
            }
            key = null;
            keyId = null;
            return;
        }
        byte[] bytes = read(file);
        key = new SecretKeySpec(bytes, "AES");
        keyId = idOf(bytes);
        for (CodePurpose purpose : CodePurpose.values()) {
            codeKeys.put(purpose, new SecretKeySpec(hmac(new SecretKeySpec(bytes, HMAC),
                    purpose.label.getBytes(StandardCharsets.UTF_8)), HMAC));
        }
        Arrays.fill(bytes, (byte) 0);
    }

    /**
     * The stored form of a recovery or enrolment code (D139 amended 2026-11-13, S56 review): HMAC-SHA256 of the
     * normalized code ({@link MfaCodes#normalize}) under the purpose's sub-key, hex. A database dump alone cannot test
     * guesses against it. Store {@link #keyId()} with it.
     */
    public String codeHash(CodePurpose purpose, String code) {
        requireKey();
        return HexFormat.of().formatHex(hmac(codeKeys.get(purpose),
                MfaCodes.normalize(code).getBytes(StandardCharsets.UTF_8)));
    }

    /** Whether {@code code} is the code behind {@code storedHash}; compared in constant time. */
    public boolean matches(CodePurpose purpose, String code, String storedHash) {
        if (storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(codeHash(purpose, code).getBytes(StandardCharsets.US_ASCII),
                storedHash.strip().getBytes(StandardCharsets.US_ASCII));
    }

    /** Whether a key is loaded; without one (MFA {@code OFF}, no key file) nobody can enrol. */
    public boolean configured() {
        return key != null;
    }

    /** A short public fingerprint of the key (not the key): the first 16 hex digits of its SHA-256. */
    public String keyId() {
        return keyId;
    }

    public Sealed seal(byte[] secret, UUID accountId) {
        requireKey();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(bytesOf(accountId));
            return new Sealed(cipher.doFinal(secret), nonce, keyId);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Sealing a second-factor secret failed", e);
        }
    }

    /** @throws IllegalStateException when the ciphertext, nonce, account or key do not match (tampering) */
    public byte[] open(Sealed sealed, UUID accountId) {
        requireKey();
        if (!keyId.equals(sealed.keyId())) {
            throw new IllegalStateException("The secret was sealed with key " + sealed.keyId() + ", not " + keyId);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed.nonce()));
            cipher.updateAAD(bytesOf(accountId));
            return cipher.doFinal(sealed.ciphertext());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("A second-factor secret does not open: tampered or another key", e);
        }
    }

    private static byte[] hmac(SecretKeySpec macKey, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(macKey);
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(HMAC + " is not available", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("No MFA key is configured (" + PROPERTY + ")");
        }
    }

    private static byte[] read(Path file) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(Files.readString(file).strip());
        } catch (IOException e) {
            throw new IllegalStateException(PROPERTY + " cannot be read: " + file, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PROPERTY + " is not base64: " + file);
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(PROPERTY + " must hold 32 bytes in base64, not " + bytes.length);
        }
        return bytes;
    }

    private static String idOf(byte[] key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key), 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static byte[] bytesOf(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .array();
    }
}
