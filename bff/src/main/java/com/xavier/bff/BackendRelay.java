package com.xavier.bff;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sends one browser call to the backend once, as the logged-in user: the BFF's bearer token and selected
 * tenant replace whatever the browser sent, and nothing that identifies the BFF session goes either way.
 * The backend's answer, a 401 step-up challenge included, comes back unchanged otherwise.
 */
@Component
class BackendRelay {

    /** Browser credentials, values only the BFF may set, and hop-by-hop or length headers. */
    private static final Set<String> NOT_TO_BACKEND = Set.of(
            "cookie", "authorization", "x-tenant-id", "x-xsrf-token", "x-csrf-token",
            "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-port", "x-real-ip",
            "host", "connection", "keep-alive", "proxy-authorization", "proxy-connection", "te", "trailer",
            "transfer-encoding", "upgrade", "expect", "content-length");

    /** The backend must not set cookies on the BFF origin; the rest is hop-by-hop or recomputed. */
    private static final Set<String> NOT_TO_BROWSER = Set.of(
            "set-cookie", "set-cookie2", "connection", "keep-alive", "proxy-authenticate", "te", "trailer",
            "transfer-encoding", "upgrade", "content-length");

    private final BffProperties properties;
    private final HttpClient http;

    BackendRelay(BffProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.backendTimeout())
                .build();
    }

    ResponseEntity<byte[]> relay(HttpServletRequest request, String accessToken, Optional<UUID> tenant) throws IOException {
        HttpRequest.Builder outgoing = HttpRequest.newBuilder(target(request))
                .timeout(properties.backendTimeout())
                .method(request.getMethod(), body(request));
        for (String name : Collections.list(request.getHeaderNames())) {
            if (!NOT_TO_BACKEND.contains(name.toLowerCase())) {
                Collections.list(request.getHeaders(name)).forEach(value -> outgoing.header(name, value));
            }
        }
        outgoing.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        tenant.ifPresent(id -> outgoing.header("X-Tenant-Id", id.toString()));
        try {
            return toBrowser(http.send(outgoing.build(), HttpResponse.BodyHandlers.ofByteArray()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for the backend", e);
        }
    }

    private URI target(HttpServletRequest request) {
        String query = request.getQueryString();
        return URI.create(properties.backendUrl() + request.getRequestURI() + (query == null ? "" : "?" + query));
    }

    static final class RequestBodyTooLargeException extends RuntimeException {
    }

    private HttpRequest.BodyPublisher body(HttpServletRequest request) throws IOException {
        int max = Math.toIntExact(properties.maxRequestBody().toBytes());
        // Reads at most one byte past the limit, whether the body has a Content-Length or is chunked.
        byte[] bytes = request.getInputStream().readNBytes(max + 1);
        if (bytes.length > max) {
            throw new RequestBodyTooLargeException();
        }
        return bytes.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(bytes);
    }

    private static ResponseEntity<byte[]> toBrowser(HttpResponse<byte[]> response) {
        HttpHeaders headers = new HttpHeaders();
        response.headers().map().forEach((name, values) -> {
            if (!name.startsWith(":") && !NOT_TO_BROWSER.contains(name.toLowerCase())) {
                headers.addAll(name, values);
            }
        });
        byte[] body = response.body();
        // status(int): a code Spring has no constant for (499, 599) passes through instead of failing.
        return ResponseEntity.status(response.statusCode()).headers(headers).body(body.length == 0 ? null : body);
    }
}
