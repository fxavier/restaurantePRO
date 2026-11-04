package com.xavier.backend.organization.controller;

import static com.xavier.backend.organization.Actors.admin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.Permission;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** With the real security configuration: a device without a session can enroll, CSRF still applies. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN"
})
class TerminalEnrollmentSecurityTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Test
    void aDeviceWithoutASessionEnrollsWithItsCode() throws Exception {
        String code = newEnrollmentCode();

        mvc.perform(post("/api/v1/auth/terminals/enroll").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(code)))
                .andExpect(status().isForbidden()); // no CSRF token
        mvc.perform(post("/api/v1/auth/terminals/enroll").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(code)))
                .andExpect(status().isOk());
    }

    @Test
    void theBackOfficeTerminalRoutesNeedASession() throws Exception {
        mvc.perform(post(TerminalHttpTest.TERMINALS).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(TerminalHttpTest.register(UUID.randomUUID(), "POS1")))
                .andExpect(status().isUnauthorized());
    }

    private String newEnrollmentCode() throws Exception {
        String store = JSON.readTree(mvc.perform(post(StoreHttpTest.STORES).with(admin(Permission.STORE_MANAGE))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(StoreHttpTest.create(StoreHttpTest.newCode(), "Africa/Maputo")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        String terminal = JSON.readTree(mvc.perform(post(TerminalHttpTest.TERMINALS)
                        .with(admin(Permission.TERMINAL_MANAGE)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(TerminalHttpTest.register(UUID.fromString(store), "POS1")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        return JSON.readTree(mvc.perform(post(TerminalHttpTest.TERMINALS + "/" + terminal + "/enrollment-code")
                        .with(admin(Permission.TERMINAL_MANAGE)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("code").asString();
    }
}
