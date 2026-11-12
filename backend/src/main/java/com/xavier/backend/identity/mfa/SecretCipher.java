package com.xavier.backend.identity.mfa;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Seals TOTP secrets at rest (D139): AES-256-GCM, a fresh 96-bit nonce per secret, the account id as associated data
 * (a sealed secret copied onto another account does not open), and the key from {@code mfa.key-file}. A database dump
 * alone then cannot generate codes. The installation does not start when MFA is on and the key is missing or not 32
 * bytes, nor when a key file is configured but unreadable. Never logs a secret or the key.
 */
@Component
public class SecretCipher {

    /** A sealed secret as stored on the factor; {@code keyId} names the key for a later rotation. */
    public record Sealed(byte[] ciphertext, byte[] nonce, String keyId) {
    }

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String PROPERTY = "restaurantpro.security.mfa.key-file";

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;
    private final String keyId;

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
