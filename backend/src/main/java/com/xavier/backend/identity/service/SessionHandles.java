package com.xavier.backend.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Opaque public names for sessions. A Spring Session id is the cookie value, so it is a bearer secret: an admin
 * listing sessions must not learn it (they could take the session over and act as that user, breaking the audit
 * trail). The handle is a truncated SHA-256 of the id: stable, unguessable, and useless as a cookie.
 */
public final class SessionHandles {

    private SessionHandles() {
    }

    public static String of(String sessionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
