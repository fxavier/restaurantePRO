package com.xavier.backend.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One HTTP client with its own credentials, like one tab of a client, against the real embedded server (D176: no
 * cookie, no CSRF on the API). A BackOffice tab sends {@code Authorization: Bearer <access token>}, a fresh test token
 * of its Keycloak user on every request (as the BFF relays one, P69); a POS tab sends {@code X-Pos-Session} after its
 * PIN login (D182, interim until S73). Every response is recorded as "METHOD path [step] -> status" in the journey the
 * caller prints.
 */
final class Browser {

    static final JsonMapper JSON =
            JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    /** Kept so the journeys read as before; the API has no CSRF token any more (D176), so both send the same. */
    enum Csrf { SEND, OMIT }

    private final String name;
    private final int port;
    private final List<String> journey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    /** A fresh access token per request (a BackOffice tab), or {@code null}. */
    private volatile Supplier<String> bearer;
    /** The POS session token (a POS tab), or {@code null}. */
    private volatile String posSession;
    /** The R2 device token of the terminal this POS tab enrolled (interim until S73), or {@code null}. */
    private volatile String deviceToken;
    /** The client address Caddy would forward ({@code X-Forwarded-For}); {@code null}: none, loopback. */
    private volatile String clientAddress;

    Browser(String name, int port, List<String> journey) {
        this.name = name;
        this.port = port;
        this.journey = journey;
    }

    /** A fresh {@code Idempotency-Key} header (the POS sends one per command, spec §9). */
    static Map<String, String> idem() {
        return Map.of("Idempotency-Key", UUID.randomUUID().toString());
    }

    /** Another tab against the same server, recording into the same journey (no credentials yet), same address. */
    Browser sibling(String siblingName) {
        Browser sibling = new Browser(siblingName, port, journey);
        sibling.clientAddress = clientAddress;
        return sibling;
    }

    /** The same tab (credentials and all) against another server: the client of a restarted instance. */
    Browser movedTo(int otherPort) {
        Browser moved = new Browser(name, otherPort, journey);
        moved.bearer = bearer;
        moved.posSession = posSession;
        moved.deviceToken = deviceToken;
        moved.clientAddress = clientAddress;
        return moved;
    }

    /**
     * From now on this tab is at {@code address}, as Caddy reports it in {@code X-Forwarded-For}; the server must
     * run with {@code server.forward-headers-strategy=native} (as in Compose), whose RemoteIpValve trusts the
     * loopback proxy. {@code null}: no header, the loopback address.
     */
    Browser at(String address) {
        clientAddress = address;
        return this;
    }

    String name() {
        return name;
    }

    int port() {
        return port;
    }

    /** From now on this tab is the BackOffice user whose tokens {@code tokens} issues (a fresh one per request). */
    Browser signedIn(Supplier<String> tokens) {
        bearer = tokens;
        posSession = null;
        return this;
    }

    /** From now on this tab is the POS session {@code token} (from the PIN login). */
    Browser posSession(String token) {
        posSession = token;
        bearer = null;
        return this;
    }

    /** This POS tab's terminal is enrolled with {@code token}. */
    Browser enrolled(String token) {
        deviceToken = token;
        return this;
    }

    String deviceToken() {
        return deviceToken;
    }

    /** No credentials any more (logged out). */
    Browser signedOut() {
        bearer = null;
        posSession = null;
        return this;
    }

    /** The credential headers this tab sends (for a second connection of the same tab, e.g. SSE). */
    Map<String, String> credentials() {
        Map<String, String> headers = new LinkedHashMap<>();
        Supplier<String> tokens = bearer;
        if (tokens != null) {
            headers.put("Authorization", "Bearer " + tokens.get());
        }
        if (posSession != null) {
            headers.put("X-Pos-Session", posSession);
        }
        return headers;
    }

    JsonNode get(String path, int expected) throws Exception {
        return expect(send("GET", path, null, Csrf.OMIT, Map.of()), expected, null);
    }

    JsonNode post(String path, Object body, int expected) throws Exception {
        return post(path, body, expected, Csrf.SEND, Map.of(), null);
    }

    JsonNode post(String path, Object body, int expected, String step) throws Exception {
        return post(path, body, expected, Csrf.SEND, Map.of(), step);
    }

    JsonNode post(String path, Object body, int expected, Csrf csrf) throws Exception {
        return post(path, body, expected, csrf, Map.of(), csrf == Csrf.OMIT ? "without CSRF token" : null);
    }

    JsonNode post(String path, Object body, int expected, Csrf csrf, Map<String, String> headers, String step)
            throws Exception {
        return expect(send("POST", path, body, csrf, headers), expected, step);
    }

    /** A POST with a fresh {@code Idempotency-Key}. */
    JsonNode command(String path, Object body, int expected, String step) throws Exception {
        return post(path, body, expected, Csrf.SEND, idem(), step);
    }

    /** A PUT with a fresh {@code Idempotency-Key}. */
    JsonNode put(String path, Object body, int expected, String step) throws Exception {
        return expect(send("PUT", path, body, Csrf.SEND, idem()), expected, step);
    }

    /** The body of a 200 response, {@code null} for a 401 (not there yet: keep polling); anything else fails. */
    JsonNode tryPost(String path, Object body) throws Exception {
        HttpResponse<String> response = send("POST", path, body, Csrf.SEND, Map.of());
        if (response.statusCode() == 401) {
            return null;
        }
        assertThat(response.statusCode()).as("POST %s (%s) answered %s", path, name, response.body())
                .isEqualTo(200);
        record("login", response);
        return JSON.readTree(response.body());
    }

    HttpResponse<String> send(String method, String path, Object body, Csrf csrf, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        credentials().forEach(request::header);
        if (clientAddress != null) {
            request.header("X-Forwarded-For", clientAddress);
        }
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * A {@code multipart/form-data} POST with one file part named {@code file} and the given form fields as query
     * parameters (as the BackOffice upload form sends a provider statement), with the credentials and {@code headers}.
     */
    JsonNode upload(String path, Map<String, String> fields, String filename, byte[] content, int expected,
                    Map<String, String> headers, String step) throws Exception {
        String boundary = "----r3" + UUID.randomUUID().toString().replace("-", "");
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: text/csv\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String query = String.join("&", fields.entrySet().stream().map(f -> f.getKey() + "="
                + java.net.URLEncoder.encode(f.getValue(), java.nio.charset.StandardCharsets.UTF_8)).toList());
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path
                        + (query.isEmpty() ? "" : "?" + query)))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        credentials().forEach(request::header);
        headers.forEach(request::header);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return expect(response, expected, step == null ? null : step);
    }

    /** The raw body of a POST that answers {@code expected} (e.g. a CSV export), with a fresh Idempotency-Key. */
    String postForText(String path, Object body, int expected, String step) throws Exception {
        HttpResponse<String> response = send("POST", path, body, Csrf.SEND, idem());
        record(name + ": " + step, response);
        assertThat(response.statusCode()).as("POST %s (%s) answered %s", path, name, response.body())
                .isEqualTo(expected);
        return response.body();
    }

    void record(String step, HttpResponse<?> response) {
        journey.add(response.request().method() + " " + response.request().uri().getRawPath()
                + (step == null ? "" : " [" + step + "]") + " -> " + response.statusCode());
    }

    private JsonNode expect(HttpResponse<String> response, int expected, String step) {
        record(step == null ? null : name + ": " + step, response);
        assertThat(response.statusCode())
                .as("%s %s (%s) answered %s", response.request().method(), response.request().uri(), name,
                        response.body())
                .isEqualTo(expected);
        return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
    }
}
