package com.xavier.bridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * The bridge client's access tokens (D182, D187): client credentials at the devices realm, authenticated with an ES256
 * client assertion (RFC 7523) signed by the device's own key; no secret exists. A token is reused until 30 s before it
 * expires. Never logs a token or an assertion.
 */
final class AccessTokens implements Supplier<String> {

    private static final Duration ASSERTION_LIFETIME = Duration.ofSeconds(60);
    private static final Duration RENEW_BEFORE = Duration.ofSeconds(30);
    private static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private final URI issuer;
    private final String clientId;
    private final PrivateKey key;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private String token;
    private Instant expiresAt = Instant.MIN;

    /** @param issuer the devices realm's issuer ({@code …/realms/restaurantpro-devices}), the assertion's audience */
    AccessTokens(URI issuer, String clientId, PrivateKey key, Clock clock) {
        this.issuer = issuer;
        this.clientId = clientId;
        this.key = key;
        this.clock = clock;
    }

    @Override
    public synchronized String get() {
        if (token == null || !clock.instant().isBefore(expiresAt.minus(RENEW_BEFORE))) {
            renew();
        }
        return token;
    }

    private void renew() {
        Instant asked = clock.instant();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", clientId);
        form.put("client_assertion_type", ASSERTION_TYPE);
        form.put("client_assertion", assertion(asked));
        String body = form.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(),
                StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(issuer
                            + "/protocol/openid-connect/token")).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("The devices realm refused a token (" + response.statusCode() + ")");
            }
            JsonNode answer = BackendClient.JSON.readTree(response.body());
            token = answer.get("access_token").asString();
            expiresAt = asked.plusSeconds(answer.get("expires_in").asLong());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while asking for a token", e);
        }
    }

    /** iss = sub = the client, aud = the realm's issuer, one minute, a unique jti. */
    private String assertion(Instant now) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", clientId);
        claims.put("sub", clientId);
        claims.put("aud", issuer.toString());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(ASSERTION_LIFETIME).getEpochSecond());
        String signingInput = base64Url(BackendClient.JSON.writeValueAsBytes(Map.of("alg", "ES256", "typ", "JWT")))
                + "." + base64Url(BackendClient.JSON.writeValueAsBytes(claims));
        try {
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign the client assertion", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
