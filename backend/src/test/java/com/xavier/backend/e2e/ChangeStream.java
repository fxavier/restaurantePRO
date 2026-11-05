package com.xavier.backend.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * A POS tab's subscription to {@code GET /api/v1/pos/notifications} (D60) over a real connection, with the tab's
 * cookies, read line by line on a virtual thread. {@link #notices()} are the {@code data:} payloads seen so far.
 */
final class ChangeStream implements AutoCloseable {

    static final String PATH = "/api/v1/pos/notifications";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final CompletableFuture<HttpResponse<Stream<String>>> response;

    private ChangeStream(Browser tab) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + tab.port() + PATH))
                .header("Accept", "text/event-stream").header("Cookie", tab.cookieHeader()).GET().build();
        response = http.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
        response.thenAcceptAsync(r -> r.body().forEach(lines::add),
                command -> Thread.ofVirtual().start(command));
    }

    /**
     * Subscribes. The response headers arrive only with the first event, and the emitter is registered on the server
     * some time after this returns; a notice committed before that is lost (D60: no replay). A caller that needs the
     * stream live therefore uses {@link #makeLive}.
     */
    static ChangeStream subscribe(Browser tab) {
        return new ChangeStream(tab);
    }

    /** A write of the stream's own store that commits a change notice; returns the changed id the notice names. */
    @FunctionalInterface
    interface ControlWrite {
        UUID write(int attempt) throws Exception;
    }

    /**
     * Repeats a fresh control write (up to 20 times, 500 ms apart) until the stream receives the notice naming that
     * write's id; from then on the emitter is registered, so every later commit reaches it. Returns every id written,
     * including those whose notice was lost before the subscription took effect.
     */
    List<UUID> makeLive(ControlWrite control, String what) throws Exception {
        List<UUID> written = new java.util.ArrayList<>();
        for (int attempt = 1; attempt <= 20; attempt++) {
            UUID id = control.write(attempt);
            written.add(id);
            long deadline = System.nanoTime() + Duration.ofMillis(500).toNanos();
            while (System.nanoTime() < deadline) {
                if (notices().stream().anyMatch(n -> n.hasNonNull("id") && n.get("id").asString().equals(id.toString()))) {
                    return written;
                }
                Thread.sleep(20);
            }
        }
        throw new AssertionError("The change stream never became live: " + what + "; got " + notices());
    }

    /** Available once the first event arrived. */
    int status() throws Exception {
        return response.get(10, TimeUnit.SECONDS).statusCode();
    }

    String contentType() throws Exception {
        return response.get(10, TimeUnit.SECONDS).headers().firstValue("Content-Type").orElse("");
    }

    /** Every {@code data:} line received so far, parsed. */
    List<JsonNode> notices() {
        return lines.stream().filter(line -> line.startsWith("data:"))
                .map(line -> Browser.JSON.readTree(line.substring("data:".length()))).toList();
    }

    @Override
    public void close() {
        http.shutdownNow();
    }
}
