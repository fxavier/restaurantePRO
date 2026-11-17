package com.xavier.bridge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Map;

/**
 * The bridge's one-time enrolment (D182, D187): a new key pair, its private key saved to {@code keyFile}, and the
 * administrator's code sent with the public key to {@code POST /api/v1/bridge/enrolment} (no token: the code is the
 * credential). The backend registers the key on the bridge's own client and answers the client id.
 */
final class BridgeEnrolment {

    private BridgeEnrolment() {
    }

    /** @return the bridge's Keycloak client id */
    static String enrol(URI backend, String code, Path keyFile) throws IOException, InterruptedException {
        KeyPair keys = DeviceKeys.generate();
        DeviceKeys.save(keys.getPrivate(), keyFile);
        String body = BackendClient.JSON.writeValueAsString(Map.of("code", code,
                "publicJwk", DeviceKeys.publicJwk((ECPublicKey) keys.getPublic())));
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        backend.resolve("/api/v1/bridge/enrolment")).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Enrolment refused (" + response.statusCode() + "): ask for a new code");
        }
        return BackendClient.JSON.readTree(response.body()).get("clientId").asString();
    }
}
