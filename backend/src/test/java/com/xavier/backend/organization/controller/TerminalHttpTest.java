package com.xavier.backend.organization.controller;

import static com.xavier.backend.organization.Actors.admin;
import static com.xavier.backend.organization.Actors.inStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.organization.MutableClock;
import com.xavier.backend.organization.OrganizationModuleTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@OrganizationModuleTest
@ExtendWith(OutputCaptureExtension.class)
class TerminalHttpTest {

    static final String TERMINALS = "/api/v1/backoffice/terminals";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    UUID store;

    @BeforeEach
    void newStore() throws Exception {
        String json = mvc.perform(post(StoreHttpTest.STORES).with(admin(Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(StoreHttpTest.create(StoreHttpTest.newCode(), "Africa/Maputo")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        store = UUID.fromString(JSON.readTree(json).get("id").asString());
    }

    @Test
    void aStoreAdminRegistersATerminalAndItIsAudited() throws Exception {
        JsonNode terminal = JSON.readTree(mvc.perform(post(TERMINALS).with(inStore(store, Permission.TERMINAL_MANAGE))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(register(store, "POS1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andExpect(jsonPath("$.code").value("POS1"))
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andReturn().getResponse().getContentAsString());

        assertThat(audits("TERMINAL_CREATED", terminal.get("id").asString())).isEqualTo(1);
    }

    @Test
    void aTerminalEnrollsOnceWithAOneTimeCodeAndOnlyHashesAreStored(CapturedOutput output) throws Exception {
        UUID terminal = registerTerminal();

        JsonNode issued = issueCode(terminal);
        String code = issued.get("code").asString();
        assertThat(Instant.parse(issued.get("expiresAt").asString())).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
        assertThat(column("enrollment_code_hash", terminal)).isEqualTo(sha256(code));

        JsonNode enrolled = JSON.readTree(mvc.perform(enroll(code))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.terminalId").value(terminal.toString()))
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andReturn().getResponse().getContentAsString());
        String token = enrolled.get("deviceToken").asString();

        assertThat(token).hasSizeGreaterThanOrEqualTo(43); // 256 random bits
        assertThat(column("device_token_hash", terminal)).isEqualTo(sha256(token));
        assertThat(column("enrollment_code_hash", terminal)).isNull(); // consumed
        assertThat(jdbc.sql("SELECT t::text FROM organization.terminal t WHERE id = :id").param("id", terminal)
                .query(String.class).single()).doesNotContain(token).doesNotContain(code);

        // the code works once, and the token is never handed out again
        mvc.perform(enroll(code)).andExpect(status().isUnprocessableContent());
        String listing = mvc.perform(get(TERMINALS).param("storeId", store.toString())
                        .with(inStore(store, Permission.TERMINAL_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(listing).doesNotContain(token);

        assertThat(output.getAll()).doesNotContain(code).doesNotContain(token);
        assertThat(audits("TERMINAL_ENROLLMENT_CODE_ISSUED", terminal.toString())).isEqualTo(1);
        assertThat(audits("TERMINAL_ENROLLED", terminal.toString())).isEqualTo(1);
    }

    @Test
    void anEnrollmentCodeExpiresAfter24Hours() throws Exception {
        UUID terminal = registerTerminal();
        String code = issueCode(terminal).get("code").asString();

        clock.advance(Duration.ofHours(24));

        mvc.perform(enroll(code)).andExpect(status().isUnprocessableContent());
        assertThat(column("status", terminal)).isEqualTo("REGISTERED");
    }

    @Test
    void anUnknownEnrollmentCodeIsRejected() throws Exception {
        mvc.perform(enroll("NOT-A-REAL-CODE")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void aRevokedTerminalCannotBeRevokedAgainOrGetANewCode() throws Exception {
        UUID terminal = registerTerminal();
        mvc.perform(enroll(issueCode(terminal).get("code").asString())).andExpect(status().isOk());

        mvc.perform(post(TERMINALS + "/" + terminal + "/revoke").with(inStore(store, Permission.TERMINAL_MANAGE))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        assertThat(audits("TERMINAL_REVOKED", terminal.toString())).isEqualTo(1);
        mvc.perform(post(TERMINALS + "/" + terminal + "/revoke").with(inStore(store, Permission.TERMINAL_MANAGE))
                .with(csrf())).andExpect(status().isConflict());
        mvc.perform(post(TERMINALS + "/" + terminal + "/enrollment-code")
                .with(inStore(store, Permission.TERMINAL_MANAGE)).with(csrf())).andExpect(status().isConflict());
    }

    @Test
    void anotherStoresAdminCannotManageThisStoresTerminals() throws Exception {
        UUID terminal = registerTerminal();
        UUID otherStore = UUID.randomUUID();

        mvc.perform(post(TERMINALS).with(inStore(otherStore, Permission.TERMINAL_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(register(store, "POS9")))
                .andExpect(status().isForbidden());
        mvc.perform(get(TERMINALS).param("storeId", store.toString())
                .with(inStore(otherStore, Permission.TERMINAL_MANAGE))).andExpect(status().isForbidden());
        mvc.perform(post(TERMINALS + "/" + terminal + "/enrollment-code")
                .with(inStore(otherStore, Permission.TERMINAL_MANAGE)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(TERMINALS + "/" + terminal + "/revoke")
                .with(inStore(otherStore, Permission.TERMINAL_MANAGE)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(TERMINALS + "/" + terminal + "/revoke")
                .with(inStore(store, Permission.STORE_MANAGE)).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void terminalCodesAreUniqueWithinAStore() throws Exception {
        registerTerminal();

        mvc.perform(post(TERMINALS).with(inStore(store, Permission.TERMINAL_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(register(store, "POS1")))
                .andExpect(status().isConflict());
    }

    @Test
    void aTerminalNeedsAnExistingStore() throws Exception {
        mvc.perform(post(TERMINALS).with(admin(Permission.TERMINAL_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(register(UUID.randomUUID(), "POS1")))
                .andExpect(status().isNotFound());
    }

    UUID registerTerminal() throws Exception {
        String json = mvc.perform(post(TERMINALS).with(inStore(store, Permission.TERMINAL_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(register(store, "POS1")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(json).get("id").asString());
    }

    JsonNode issueCode(UUID terminal) throws Exception {
        return JSON.readTree(mvc.perform(post(TERMINALS + "/" + terminal + "/enrollment-code")
                        .with(inStore(store, Permission.TERMINAL_MANAGE)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsString());
    }

    /**
     * The enroll route is open in production ({@code /api/v1/auth/**}, see TerminalEnrollmentSecurityTest);
     * this module test boots without the config module, so it sends an actor with no permissions.
     */
    static MockHttpServletRequestBuilder enroll(String code) {
        return post("/api/v1/auth/terminals/enroll").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"%s\"}".formatted(code));
    }

    String column(String name, UUID terminal) {
        return jdbc.sql("SELECT " + name + " FROM organization.terminal WHERE id = :id").param("id", terminal)
                .query(String.class).optional().orElse(null);
    }

    static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    long audits(String action, String terminalId) {
        return jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event
                        WHERE action = :action AND terminal_id = :terminal AND store_id = :store""")
                .param("action", action).param("terminal", UUID.fromString(terminalId)).param("store", store)
                .query(Long.class).single();
    }

    static String register(UUID storeId, String code) {
        return """
                {"storeId": "%s", "code": "%s", "name": "Bar"}""".formatted(storeId, code);
    }
}
