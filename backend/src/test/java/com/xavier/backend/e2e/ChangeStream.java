package com.xavier.backend.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
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
     * Subscribes. The response headers arrive only with the first event, so a caller that needs to know the stream is
     * registered commits a change of its own and waits for it ({@link #awaitNotice}).
     */
    static ChangeStream subscribe(Browser tab) {
        return new ChangeStream(tab);
    }

    /** Waits up to 10 s for a notice matching {@code match}; the stream is then live for every later commit. */
    void awaitNotice(java.util.function.Predicate<JsonNode> match, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (notices().stream().noneMatch(match)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("No change notice: " + what + "; got " + notices());
            }
            Thread.sleep(20);
        }
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
