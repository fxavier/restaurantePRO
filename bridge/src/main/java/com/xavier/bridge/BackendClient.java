package com.xavier.bridge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The backend's bridge API (D187), outbound only: claim the store's jobs ({@code GET /api/v1/bridge/jobs}, a long
 * poll) and report a send's outcome ({@code POST /api/v1/bridge/jobs/{id}/outcome}), each with a fresh access token
 * of the bridge's client. The bridge never names its store or tenant: its membership does.
 */
final class BackendClient {

    static final JsonMapper JSON = JsonMapper.builder().build();
    /** Beyond the longest wait the backend holds a claim (30 s). */
    private static final Duration CLAIM_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);

    private final URI backend;
    private final Supplier<String> accessTokens;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    BackendClient(URI backend, Supplier<String> accessTokens) {
        this.backend = backend;
        this.accessTokens = accessTokens;
    }

    /** The jobs the backend leased to this bridge, waiting up to {@code wait} for one. */
    List<ClaimedJob> claim(Duration wait) throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(backend.resolve("/api/v1/bridge/jobs?waitSeconds="
                + wait.toSeconds())).timeout(CLAIM_TIMEOUT).GET());
        return JSON.readTree(response.body()).valueStream().map(BackendClient::job).toList();
    }

    /** What the send of {@code job} achieved; a repeat changes nothing at the backend. */
    void report(UUID job, SendResult result) throws IOException, InterruptedException {
        String body = JSON.writeValueAsString(Map.of("outcome", result.outcome().name(),
                "paperNearEnd", result.paperNearEnd()));
        send(HttpRequest.newBuilder(backend.resolve("/api/v1/bridge/jobs/" + job + "/outcome")).timeout(CALL_TIMEOUT)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request.header("Authorization", "Bearer " + accessTokens.get())
                .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IOException("The backend answered " + response.statusCode() + " to " + response.request()
                    .method() + " " + response.uri().getPath());
        }
        return response;
    }

    private static ClaimedJob job(JsonNode job) {
        return new ClaimedJob(UUID.fromString(job.get("id").asString()), job.get("host").asString(),
                job.get("port").asInt(), Base64.getDecoder().decode(job.get("payload").asString()),
                job.get("probeStatus").asBoolean());
    }
}
