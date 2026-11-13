package com.xavier.backend.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.WithActor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D152: besides the scrape token, {@code /actuator/prometheus} answers an installation-wide {@code SYSTEM_MONITOR}
 * actor and refuses any other. Actuator is served on the application port here so MockMvc reaches it with the
 * actor; on a real installation it is the management port ({@link MetricsScrapeTokenTest}).
 */
@SpringBootTest(properties = {"management.server.port=8080",
        "restaurantpro.metrics.token-file=src/test/resources/config/metrics-token.txt"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MetricsScrapeSessionTest {

    @Autowired
    MockMvc mvc;

    @Test
    @WithActor(permissions = Permission.SYSTEM_MONITOR)
    void aSystemMonitorActorMayScrape() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    @WithActor(permissions = Permission.SYSTEM_MONITOR, storeId = "0190a000-0000-7000-8000-0000000061aa")
    void aStoreScopedSystemMonitorMayNot() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isForbidden());
    }

    @Test
    @WithActor(permissions = Permission.REPORT_VIEW)
    void anotherPermissionMayNot() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isForbidden());
    }
}
