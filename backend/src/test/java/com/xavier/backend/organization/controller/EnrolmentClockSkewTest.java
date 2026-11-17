package com.xavier.backend.organization.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.config.security.DeviceHttpTest;
import com.xavier.backend.config.security.Devices;
import com.xavier.backend.config.security.Devices.Device;
import com.xavier.backend.identity.IdentityFixtures;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The application and the database are separate machines whose clocks disagree. A membership granted for immediate
 * use (a device's pending enrolment, then its own) must be valid at once by the database's clock, which resolves it,
 * even when the application's clock runs ahead.
 */
@DeviceHttpTest
@Import(EnrolmentClockSkewTest.AheadOfTheDatabase.class)
class EnrolmentClockSkewTest {

    static final Duration AHEAD = Duration.ofSeconds(5);

    @Autowired
    MockMvc mvc;

    @Test
    void aDeviceEnrolsAndActsAtOnceWhenTheApplicationClockIsAheadOfTheDatabase() throws Exception {
        UUID store = new IdentityFixtures(mvc).store();

        Device bridge = new Devices(mvc).enrolledBridge(store);

        mvc.perform(get("/api/v1/bridge/jobs").with(bridge.bearer())).andExpect(status().isNoContent());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AheadOfTheDatabase {

        @Bean
        @Primary
        Clock aheadOfTheDatabase() {
            return Clock.offset(Clock.systemUTC(), AHEAD);
        }
    }
}
