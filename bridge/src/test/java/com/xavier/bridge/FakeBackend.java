package com.xavier.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.json.JsonMapper;

/**
 * The backend's bridge API on 127.0.0.1, as {@code BridgeJobController} answers it (D187): each claim hands out the next
 * queued batch (then none), each outcome and enrolment is kept. What every request carried is recorded.
 */
final class FakeBackend implements AutoCloseable {

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** One request as it arrived. */
    record Request(String method, String path, String query, String authorization, String body) {
    }

    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Deque<List<Map<String, Object>>> batches = new ArrayDeque<>();
    private final HttpServer server;
    private volatile int outcomeStatus = 204;

    FakeBackend() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** The jobs the next claim answers. */
    synchronized void queue(List<Map<String, Object>> jobs) {
        batches.addLast(jobs);
    }

    void answerOutcomesWith(int status) {
        outcomeStatus = status;
    }

    List<Request> requests(String method, String pathPrefix) {
        return requests.stream().filter(r -> r.method().equals(method) && r.path().startsWith(pathPrefix)).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        requests.add(new Request(exchange.getRequestMethod(), path, exchange.getRequestURI().getQuery(),
                exchange.getRequestHeaders().getFirst("Authorization"), body));
        if (path.equals("/api/v1/bridge/jobs") && exchange.getRequestMethod().equals("GET")) {
            List<Map<String, Object>> next;
            synchronized (this) {
                next = batches.isEmpty() ? List.of() : batches.removeFirst();
            }
            respond(exchange, 200, JSON.writeValueAsString(next));
        } else if (path.endsWith("/outcome")) {
            respond(exchange, outcomeStatus, "");
        } else if (path.equals("/api/v1/bridge/enrolment")) {
            String device = "0190f000-0000-7000-8000-0000000000b1";
            respond(exchange, 200, "{\"id\":\"" + device + "\",\"storeId\":\"0190f000-0000-7000-8000-0000000000b2\","
                    + "\"clientId\":\"bridge-" + device + "\"}");
        } else {
            respond(exchange, 404, "");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
