package com.xavier.backend.config.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.access.WithActor;
import com.xavier.backend.config.alerts.AlertsIntegrationTest.CountingTestSource;
import com.xavier.backend.settlement.SettlementTestClock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A63, D149: {@code GET /api/v1/backoffice/alerts} lists every alert source's alerts to a {@code SYSTEM_MONITOR}
 * holder, CRITICAL first: a store-scoped holder sees only its stores' alerts (never an installation-wide one), a
 * failing source is one {@code ALERT_SOURCE_FAILED} warning, and the sources are asked at most every 30 s.
 */
@AlertsIntegrationTest
class AlertsEndpointTest {

    static final String PATH = "/api/v1/backoffice/alerts";

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementTestClock clock;

    @Autowired
    CountingTestSource counting;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    @WithActor(permissions = Permission.SYSTEM_MONITOR, storeId = AlertsIntegrationTest.STORE_A)
    void aStoreScopedHolderSeesOnlyItsStoresAlerts() throws Exception {
        JsonNode alerts = alerts();

        assertThat(codes(alerts)).contains("TEST_STORE_A")
                .doesNotContain("TEST_STORE_B", "TEST_INSTALLATION", "ALERT_SOURCE_FAILED");
        assertThat(alerts.valueStream().map(alert -> alert.get("storeId").asString()))
                .containsOnly(AlertsIntegrationTest.STORE_A);
    }

    @Test
    @WithActor(permissions = Permission.SYSTEM_MONITOR)
    void anInstallationWideHolderSeesEveryAlertCriticalFirst() throws Exception {
        JsonNode alerts = alerts();

        assertThat(codes(alerts)).contains("TEST_STORE_A", "TEST_STORE_B", "TEST_INSTALLATION",
                "ALERT_SOURCE_FAILED");
        List<String> severities = alerts.valueStream().map(alert -> alert.get("severity").asString()).toList();
        assertThat(severities.lastIndexOf("CRITICAL")).isLessThan(severities.indexOf("WARNING"));
        JsonNode storeB = alerts.valueStream().filter(alert -> alert.get("code").asString().equals("TEST_STORE_B"))
                .findFirst().orElseThrow();
        assertThat(storeB.get("count").asLong()).isEqualTo(2);
        assertThat(storeB.get("since").asString()).isEqualTo("2026-11-12T08:00:00Z");
        assertThat(storeB.get("summary").asString()).isEqualTo("Store B");
    }

    @Test
    @WithActor(permissions = Permission.SYSTEM_MONITOR)
    void aFailingSourceIsOneWarningNamingItInsteadOfAnError() throws Exception {
        JsonNode failed = alerts().valueStream()
                .filter(alert -> alert.get("code").asString().equals("ALERT_SOURCE_FAILED")).findFirst()
                .orElseThrow();

        assertThat(failed.get("severity").asString()).isEqualTo("WARNING");
        assertThat(failed.get("storeId").isNull()).isTrue();
        assertThat(failed.get("summary").asString()).contains("FailingTestSource")
                .doesNotContain("always fails");
    }

    @Test
    @WithActor(permissions = Permission.SYSTEM_MONITOR)
    void theSourcesAreAskedAtMostEvery30Seconds() throws Exception {
        clock.advance(Duration.ofSeconds(31));
        alerts();
        int afterFirst = counting.calls.get();

        alerts();
        assertThat(counting.calls).hasValue(afterFirst);

        clock.advance(Duration.ofSeconds(30));
        alerts();
        assertThat(counting.calls).hasValue(afterFirst + 1);
    }

    @Test
    @WithActor(permissions = Permission.REPORT_VIEW)
    void withoutSystemMonitorTheListIsForbidden() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isForbidden());
    }

    @Test
    void withoutASessionTheListIsUnauthorized() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    private JsonNode alerts() throws Exception {
        String body = mvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(body).get("alerts");
    }

    private static List<String> codes(JsonNode alerts) {
        return alerts.valueStream().map(alert -> alert.get("code").asString()).toList();
    }
}
