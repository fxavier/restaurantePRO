package com.xavier.backend.organization.model;

import com.xavier.backend.organization.exception.InvalidDeviceKeyException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * A device's public key (D182): an EC P-256 JWK as WebCrypto exports it, checked where it enters. A JWK with any
 * member beyond the public ones (a private {@code d} above all) is refused, so a private key is never stored or sent on.
 *
 * @param x the curve point's x coordinate, base64url (32 bytes)
 * @param y the curve point's y coordinate, base64url (32 bytes)
 */
public record DevicePublicKey(String x, String y) {

    private static final Set<String> PUBLIC_MEMBERS = Set.of("kty", "crv", "x", "y", "kid", "use", "alg", "ext",
            "key_ops");
    private static final String COORDINATE = "[A-Za-z0-9_-]{43}";

    public static DevicePublicKey parse(Map<String, Object> jwk) {
        if (jwk == null || !"EC".equals(jwk.get("kty")) || !"P-256".equals(jwk.get("crv"))
                || !PUBLIC_MEMBERS.containsAll(jwk.keySet())
                || !(jwk.get("x") instanceof String x) || !x.matches(COORDINATE)
                || !(jwk.get("y") instanceof String y) || !y.matches(COORDINATE)) {
            throw new InvalidDeviceKeyException();
        }
        return new DevicePublicKey(x, y);
    }

    /** The RFC 7638 thumbprint (SHA-256, base64url): the key's identity. */
    public String thumbprint() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    /** The JWK registered on the device's client: the public members, its signing use, and its thumbprint as kid. */
    public String jwk() {
        return "{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"" + x + "\",\"y\":\"" + y + "\",\"use\":\"sig\","
                + "\"alg\":\"ES256\",\"kid\":\"" + thumbprint() + "\"}";
    }

    /** RFC 7638 §3.2: the required members, lexicographic order, no whitespace. */
    private String canonical() {
        return "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"" + x + "\",\"y\":\"" + y + "\"}";
    }
}
