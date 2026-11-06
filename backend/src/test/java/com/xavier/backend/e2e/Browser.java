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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One HTTP client with its own cookie jar, like one browser tab of the SPA, against the real embedded server.
 *
 * <p>It keeps its own jar because {@code java.net.CookieManager} never sends a {@code Secure} cookie over plain HTTP;
 * in production Caddy terminates TLS in front of this same server, so a browser does send them. Like the SPA, any GET
 * hands out {@code XSRF-TOKEN} and every mutating request echoes it in {@code X-XSRF-TOKEN}. Every response is
 * recorded as "METHOD path [step] -> status" in the journey the caller prints.
 */
final class Browser {

    static final JsonMapper JSON =
            JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    enum Csrf { SEND, OMIT }

    private final String name;
    private final int port;
    private final List<String> journey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final Map<String, String> lastSetCookie = new LinkedHashMap<>();

    Browser(String name, int port, List<String> journey) {
        this.name = name;
        this.port = port;
        this.journey = journey;
    }

    /** A fresh {@code Idempotency-Key} header (the POS sends one per command, spec §9). */
    static Map<String, String> idem() {
        return Map.of("Idempotency-Key", UUID.randomUUID().toString());
    }

    /** Another tab against the same server, recording into the same journey (its own cookie jar). */
    Browser sibling(String siblingName) {
        return new Browser(siblingName, port, journey);
    }

    /** The same tab (cookies and all) against another server: the client of a restarted instance. */
    Browser movedTo(int otherPort) {
        Browser moved = new Browser(name, otherPort, journey);
        moved.cookies.putAll(cookies);
        return moved;
    }

    String name() {
        return name;
    }

    int port() {
        return port;
    }

    String cookie(String cookieName) {
        return cookies.get(cookieName);
    }

    /** The {@code Cookie} header this client sends (for a second connection of the same tab, e.g. SSE). */
    String cookieHeader() {
        return String.join("; ", cookies.entrySet().stream().map(c -> c.getKey() + "=" + c.getValue()).toList());
    }

    String setCookieHeader(String cookieName) {
        return lastSetCookie.get(cookieName);
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
        if (!cookies.isEmpty()) {
            request.header("Cookie", cookieHeader());
        }
        if (csrf == Csrf.SEND && cookies.containsKey("XSRF-TOKEN")) {
            request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        }
        headers.forEach(request::header);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        for (String header : response.headers().allValues("Set-Cookie")) {
            String pair = header.contains(";") ? header.substring(0, header.indexOf(';')) : header;
            String cookieName = pair.substring(0, pair.indexOf('='));
            String value = pair.substring(pair.indexOf('=') + 1);
            lastSetCookie.put(cookieName, header);
            if (value.isEmpty() || header.toLowerCase().contains("max-age=0")) {
                cookies.remove(cookieName);
            } else {
                cookies.put(cookieName, value);
            }
        }
        return response;
    }

    /**
     * A {@code multipart/form-data} POST with one file part named {@code file} and the given form fields as query
     * parameters (as the BackOffice upload form sends a provider statement), with the CSRF token and {@code headers}.
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
        if (!cookies.isEmpty()) {
            request.header("Cookie", cookieHeader());
        }
        if (cookies.containsKey("XSRF-TOKEN")) {
            request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        }
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
