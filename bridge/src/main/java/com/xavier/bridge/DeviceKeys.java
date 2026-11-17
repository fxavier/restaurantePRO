package com.xavier.bridge;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * The bridge's own key pair (D182, D187): EC P-256, made on the device. The private key stays in a PKCS#8 PEM file only
 * the service's user may read; only the public half (a JWK) ever leaves the device.
 */
final class DeviceKeys {

    private DeviceKeys() {
    }

    static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("EC P-256 is part of every Java runtime", e);
        }
    }

    /** Writes the private key, readable and writable by its owner only, before anything else can read it. */
    static void save(PrivateKey key, Path file) throws IOException {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Files.deleteIfExists(file);
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, pem, StandardCharsets.US_ASCII);
    }

    static PrivateKey load(Path file) throws IOException, GeneralSecurityException {
        String base64 = Files.readString(file, StandardCharsets.US_ASCII)
                .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder()
                .decode(base64)));
    }

    /** The public key as a JWK (RFC 7518 §6.2): the curve point only. */
    static Map<String, Object> publicJwk(ECPublicKey key) {
        return Map.of("kty", "EC", "crv", "P-256", "x", coordinate(key.getW().getAffineX()),
                "y", coordinate(key.getW().getAffineY()));
    }

    /** 32 bytes, big-endian, base64url without padding. */
    private static String coordinate(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] fixed = new byte[32];
        int length = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - length, fixed, 32 - length, length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(fixed, 32));
    }
}
