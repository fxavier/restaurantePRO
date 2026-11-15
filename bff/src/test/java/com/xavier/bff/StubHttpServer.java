package com.xavier.bff;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** A JDK HttpServer on a random loopback port that records every request it receives. */
abstract class StubHttpServer {

    record Response(int status, Map<String, String> headers, String body) {
    }

    private final HttpServer server;
    private final List<RecordedRequest> received = new CopyOnWriteArrayList<>();

    StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::exchange);
        server.start();
    }

    protected abstract Response respond(RecordedRequest request);

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<RecordedRequest> received() {
        return List.copyOf(received);
    }

    void clearReceived() {
        received.clear();
    }

    private void exchange(HttpExchange exchange) throws IOException {
        try (exchange) {
            Map<String, List<String>> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), List.copyOf(values)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            RecordedRequest request = new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI(), headers, body);
            received.add(request);
            Response response = respond(request);
            response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        }
    }
}
