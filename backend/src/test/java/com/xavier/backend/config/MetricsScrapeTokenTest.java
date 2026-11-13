package com.xavier.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.config.alerts.AlertsIntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A63, D152: {@code /actuator/prometheus} (management port) answers a valid {@code Bearer} scrape token or a
 * {@code SYSTEM_MONITOR} session, and 401 otherwise. The token opens no other endpoint. The session half is
 * {@link MetricsScrapeSessionTest} (a session is not resolved on a separate management port).
 */
@AlertsIntegrationTest
class MetricsScrapeTokenTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @TempDir
    Path dir;

    @Test
    void withoutATokenOrASessionTheScrapeIsUnauthorized() throws Exception {
        assertThat(management("/actuator/prometheus", null).statusCode()).isEqualTo(401);
    }

    @Test
    void withTheTokenTheScrapeAnswersPrometheusText() throws Exception {
        HttpResponse<String> scrape = management("/actuator/prometheus", "Bearer " + AlertsIntegrationTest.TOKEN);

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("# TYPE ").contains("filesystem_free_ratio");
    }

    @Test
    void aWrongTokenIsUnauthorized() throws Exception {
        assertThat(management("/actuator/prometheus", "Bearer " + AlertsIntegrationTest.TOKEN + "x").statusCode())
                .isEqualTo(401);
        assertThat(management("/actuator/prometheus", AlertsIntegrationTest.TOKEN).statusCode())
                .as("not a Bearer header").isEqualTo(401);
    }

    @Test
    void theTokenOpensNoOtherEndpoint() throws Exception {
        String bearer = "Bearer " + AlertsIntegrationTest.TOKEN;

        assertThat(management("/actuator/metrics", bearer).statusCode()).isEqualTo(401);
        assertThat(send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/backoffice/alerts"))
                .header("Authorization", bearer).GET()).statusCode()).isEqualTo(401);
    }

    @Test
    void aConfiguredTokenFileMustHoldAToken() throws IOException {
        Path empty = Files.writeString(dir.resolve("metrics_token"), "  \n");

        assertThat(MetricsScrapeSecurity.tokenDigest("")).isEmpty();
        assertThatThrownBy(() -> MetricsScrapeSecurity.tokenDigest(empty.toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> MetricsScrapeSecurity.tokenDigest(dir.resolve("absent").toString()))
                .isInstanceOf(IllegalStateException.class);
    }

    private HttpResponse<String> management(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + path))
                .GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
