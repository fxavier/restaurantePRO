package com.xavier.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * D187 / A89: the bridge holds only outbound connections. It claims one job at a time from the backend, sends it to
 * the printer's LAN address over ESC/POS TCP and reports what the send achieved before it claims the next; it never
 * decides a retry (the backend does, D50 §7.2). After a job it claims again at once (the backlog drains); when there
 * was none it waits its poll interval (review 2: the backend holds no thread for it).
 */
class BridgeAgentTest {

    static final Duration POLL = Duration.ofSeconds(2);

    final FakeBackend backend = new FakeBackend();
    final PrinterSender sender = new PrinterSender(Duration.ofSeconds(2), Duration.ofSeconds(5));
    final BridgeAgent agent = new BridgeAgent(new BackendClient(backend.uri(), () -> "token-1"), sender, POLL);

    BridgeAgentTest() throws Exception {
    }

    @AfterEach
    void stop() {
        sender.close();
        backend.close();
    }

    @Test
    void aClaimedJobIsPrintedAndItsOutcomeReported() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.accepting()) {
            UUID job = UUID.randomUUID();
            backend.queue(List.of(job(job, printer.host(), printer.port(), "kitchen ticket")));

            assertThat(agent.cycle()).as("claim again at once").isEqualTo(Duration.ZERO);

            assertThat(printer.awaitPayloads(StandardCharsets.US_ASCII, "kitchen ticket"::equals, 1,
                    Duration.ofSeconds(5))).hasSize(1);
            JsonNode outcome = outcomeOf(job);
            assertThat(outcome.get("outcome").asString()).isEqualTo("ACCEPTED");
            assertThat(outcome.get("paperNearEnd").asBoolean()).isFalse();
            assertThat(backend.requests).allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer token-1"));
        }
    }

    @Test
    void anUnreachablePrinterIsReportedNotConnected() throws Exception {
        UUID job = UUID.randomUUID();
        backend.queue(List.of(job(job, "127.0.0.1", TcpFakePrinter.closedPort(), "bar ticket")));

        agent.cycle();

        assertThat(outcomeOf(job).get("outcome").asString()).isEqualTo("NOT_CONNECTED");
    }

    @Test
    void anIdleBridgeWaitsItsPollIntervalAndAsksForOneJob() throws Exception {
        assertThat(agent.cycle()).isEqualTo(POLL);

        assertThat(backend.requests("GET", "/api/v1/bridge/jobs")).extracting(FakeBackend.Request::query)
                .containsExactly("limit=1");
    }

    /** Errors back off: 2 s, doubling, at most 60 s; a success starts again from 2 s. */
    @Test
    void failuresBackOffAndASuccessResetsTheBackoff() {
        Backoff backoff = new Backoff(Duration.ofSeconds(2), Duration.ofSeconds(60));

        assertThat(java.util.stream.Stream.generate(backoff::next).limit(7).toList()).containsExactly(
                Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8), Duration.ofSeconds(16),
                Duration.ofSeconds(32), Duration.ofSeconds(60), Duration.ofSeconds(60));
        backoff.reset();
        assertThat(backoff.next()).isEqualTo(Duration.ofSeconds(2));
    }

    private JsonNode outcomeOf(UUID job) {
        List<FakeBackend.Request> reports = backend.requests("POST", "/api/v1/bridge/jobs/" + job + "/outcome");
        assertThat(reports).hasSize(1);
        return FakeBackend.JSON.readTree(reports.getFirst().body());
    }

    private static Map<String, Object> job(UUID id, String host, int port, String text) {
        return Map.of("id", id.toString(), "host", host, "port", port, "probeStatus", false,
                "payload", Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.US_ASCII)));
    }
}
