package com.xavier.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * D182 / D187: the bridge enrols like a terminal. It makes its own P-256 key pair, keeps the private key in a file only
 * its user can read, and sends only the public half with the one-time code; afterwards it gets access tokens with
 * client credentials and an ES256 client assertion (RFC 7523) signed with that key. No shared secret exists.
 */
class DeviceKeysTest {

    @TempDir
    Path dir;

    @Test
    void enrolmentSendsTheCodeAndThePublicKeyOnlyAndKeepsThePrivateKeyInAPrivateFile() throws Exception {
        Path keyFile = dir.resolve("bridge-key.pem");
        try (FakeBackend backend = new FakeBackend()) {

            String clientId = BridgeEnrolment.enrol(backend.uri(), "ABCD-EFGH-0123-4567-89AB-CDEF", keyFile);

            assertThat(clientId).startsWith("bridge-");
            JsonNode body = FakeBackend.JSON.readTree(backend.requests("POST", "/api/v1/bridge/enrolment").getFirst()
                    .body());
            assertThat(body.get("code").asString()).isEqualTo("ABCD-EFGH-0123-4567-89AB-CDEF");
            JsonNode jwk = body.get("publicJwk");
            assertThat(jwk.get("kty").asString()).isEqualTo("EC");
            assertThat(jwk.get("crv").asString()).isEqualTo("P-256");
            assertThat(jwk.has("x") && jwk.has("y")).isTrue();
            assertThat(jwk.has("d")).isFalse();
            assertThat(Files.getPosixFilePermissions(keyFile))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            assertThat(DeviceKeys.load(keyFile)).isNotNull();
        }
    }

    @Test
    void anAccessTokenIsAskedWithASignedClientAssertionAndReusedUntilItNearlyExpires() throws Exception {
        KeyPair keys = DeviceKeys.generate();
        List<Map<String, String>> forms = new CopyOnWriteArrayList<>();
        HttpServer realm = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        realm.createContext("/realms/restaurantpro-devices/protocol/openid-connect/token", exchange -> {
            forms.add(form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] answer = ("{\"access_token\":\"t" + forms.size() + "\",\"expires_in\":300}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        realm.start();
        try {
            URI issuer = URI.create("http://127.0.0.1:" + realm.getAddress().getPort()
                    + "/realms/restaurantpro-devices");
            MovableClock clock = new MovableClock(Instant.parse("2026-11-17T10:00:00Z"));
            AccessTokens tokens = new AccessTokens(issuer, "bridge-1", keys.getPrivate(), clock);

            assertThat(tokens.get()).isEqualTo("t1");
            assertThat(tokens.get()).as("reused").isEqualTo("t1");
            clock.now = clock.now.plus(Duration.ofSeconds(271));
            assertThat(tokens.get()).as("renewed 30 s before expiry").isEqualTo("t2");

            Map<String, String> form = forms.getFirst();
            assertThat(form).containsEntry("grant_type", "client_credentials").containsEntry("client_id", "bridge-1")
                    .containsEntry("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
                    .doesNotContainKey("client_secret");
            String[] assertion = form.get("client_assertion").split("\\.");
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify((ECPublicKey) keys.getPublic());
            verifier.update((assertion[0] + "." + assertion[1]).getBytes(StandardCharsets.US_ASCII));
            assertThat(verifier.verify(Base64.getUrlDecoder().decode(assertion[2]))).isTrue();
            JsonNode claims = FakeBackend.JSON.readTree(Base64.getUrlDecoder().decode(assertion[1]));
            assertThat(claims.get("iss").asString()).isEqualTo("bridge-1");
            assertThat(claims.get("sub").asString()).isEqualTo("bridge-1");
            assertThat(claims.get("aud").asString()).isEqualTo(issuer.toString());
            assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(60);
        } finally {
            realm.stop(0);
        }
    }

    private static Map<String, String> form(String body) {
        return Arrays.stream(body.split("&")).map(pair -> pair.split("=", 2)).collect(Collectors.toMap(
                pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    /** A clock a test moves. */
    static final class MovableClock extends Clock {

        volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
