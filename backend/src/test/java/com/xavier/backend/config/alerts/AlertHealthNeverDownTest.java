package com.xavier.backend.config.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * D149: the health component {@code operationalAlerts} reports {@code ALERT} while alerts are active, and the overall
 * status stays {@code UP} (the Compose healthcheck greps {@code "status":"UP"}): an alert never makes the container
 * unhealthy. This context always has alerts (its test sources).
 */
@AlertsIntegrationTest
class AlertHealthNeverDownTest {

    @LocalManagementPort
    int managementPort;

    @Test
    void withActiveAlertsTheHealthComponentIsAlertAndTheOverallStatusStaysUp() throws Exception {
        HttpResponse<String> health = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        JsonNode body = JsonMapper.builder().build().readTree(health.body());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(body.get("components").get("operationalAlerts").get("status").asString()).isEqualTo("ALERT");
        assertThat(body.get("status").asString()).isEqualTo("UP");
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }
}
