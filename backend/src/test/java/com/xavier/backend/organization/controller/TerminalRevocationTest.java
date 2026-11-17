package com.xavier.backend.organization.controller;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.config.security.DeviceHttpTest;
import com.xavier.backend.config.security.Devices;
import com.xavier.backend.config.security.Devices.Device;
import com.xavier.backend.config.security.FakeDeviceProvider;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D182 / A86: a revoked terminal is refused on its very next request, whatever token it still holds (its membership is
 * revoked in the revocation's own transaction), and its Keycloak client is disabled after the commit, so it gets no
 * new token either. Its open POS sessions end.
 */
@DeviceHttpTest
class TerminalRevocationTest {

    static final String PIN = "4821";

    @Autowired
    MockMvc mvc;

    @Test
    void aRevokedTerminalIsRefusedOnItsNextRequestAndItsClientIsDisabled() throws Exception {
        IdentityFixtures fixtures = new IdentityFixtures(mvc);
        UUID store = fixtures.store();
        Device terminal = new Devices(mvc).enrolledTerminal(store);
        Employee waiter = fixtures.employee(PIN, store, IdentityFixtures.WAITER);
        String session = IdentityFixtures.JSON.readTree(mvc.perform(post("/api/v1/auth/pos-login")
                        .with(terminal.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(IdentityFixtures.json("employeeCode", waiter.code(), "pin", PIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("posSession").asString();
        mvc.perform(get("/api/v1/auth/pos-session").with(terminal.bearer()).header("X-Pos-Session", session))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/backoffice/terminals/" + terminal.id() + "/revoke").with(admin()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/auth/pos-session").with(terminal.bearer()).header("X-Pos-Session", session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_FORBIDDEN"));
        mvc.perform(post("/api/v1/auth/pos-login").with(terminal.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(IdentityFixtures.json("employeeCode", waiter.code(), "pin", PIN)))
                .andExpect(status().isForbidden());
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .until(() -> FakeDeviceProvider.DISABLED.contains(terminal.clientId()));
        assertThat(FakeDeviceProvider.DISABLED).contains(terminal.clientId());
    }

    @Test
    void aRevokedTerminalCannotBeGivenANewCode() throws Exception {
        UUID store = new IdentityFixtures(mvc).store();
        Device terminal = new Devices(mvc).enrolledTerminal(store);
        mvc.perform(post("/api/v1/backoffice/terminals/" + terminal.id() + "/revoke").with(admin()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/backoffice/terminals/" + terminal.id() + "/enrollment-code").with(admin()))
                .andExpect(status().isConflict());
    }

    @Test
    void anEnrolledTerminalCanBeReEnrolledWithANewCode() throws Exception {
        UUID store = new IdentityFixtures(mvc).store();
        Devices devices = new Devices(mvc);
        Device terminal = devices.enrolledTerminal(store);

        devices.enrol(Devices.POS_ENROLMENT, devices.code(terminal.id()), Devices.publicJwk(),
                        Devices.randomAddress())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value(terminal.clientId()));
    }
}
