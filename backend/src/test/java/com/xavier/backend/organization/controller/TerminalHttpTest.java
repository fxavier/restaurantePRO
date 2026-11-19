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

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Permission;
import com.xavier.backend.config.security.Devices;
import com.xavier.backend.organization.MutableClock;
import com.xavier.backend.organization.OrganizationModuleTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.mock.web.MockHttpServletResponse;
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
        JsonNode terminal = JSON.readTree(mvc.perform(registration(store, "POS1", newKey()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andExpect(jsonPath("$.code").value("POS1"))
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andReturn().getResponse().getContentAsString());

        assertThat(audits("TERMINAL_CREATED", terminal.get("id").asString())).isEqualTo(1);
    }

    @Test
    void aCodeIsShownOnceAndOnlyItsSecretsHashAndAPendingMembershipAreStored(CapturedOutput output)
            throws Exception {
        UUID terminal = registerTerminal();

        JsonNode issued = issueCode(terminal);
        String code = issued.get("code").asString();
        assertThat(Instant.parse(issued.get("expiresAt").asString())).isEqualTo(clock.instant().plus(Duration.ofHours(24)));

        assertThat(code).hasSize(24);
        assertThat(column("enrollment_code_hash", terminal)).isEqualTo(sha256(code.substring(8)));
        assertThat(jdbc.sql("SELECT t::text FROM organization.terminal t WHERE id = :id").param("id", terminal)
                .query(String.class).single()).doesNotContain(code).doesNotContain(code.substring(8));
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM tenancy.tenant_membership "
                        + "WHERE issuer = 'urn:restaurantpro:enrolment-code' AND subject = ? AND principal_id = ? "
                        + "AND kind = 'TERMINAL' AND status = 'ACTIVE' AND valid_until IS NOT NULL")
                .param(code.substring(0, 8)).param(terminal).query(Long.class).single()).isEqualTo(1);
        String listing = mvc.perform(get(TERMINALS).param("storeId", store.toString())
                        .with(inStore(store, Permission.TERMINAL_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("REGISTERED"))
                .andExpect(jsonPath("$.content[0].kind").value("POS"))
                .andReturn().getResponse().getContentAsString();
        assertThat(listing).doesNotContain(code.substring(8));
        assertThat(output.getAll()).doesNotContain(code.substring(8));
        assertThat(audits("TERMINAL_ENROLLMENT_CODE_ISSUED", terminal.toString())).isEqualTo(1);
    }

    @Test
    void aNewCodeRevokesThePendingMembershipOfTheOldOne() throws Exception {
        UUID terminal = registerTerminal();
        String first = issueCode(terminal).get("code").asString();

        issueCode(terminal);

        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT status FROM tenancy.tenant_membership "
                        + "WHERE issuer = 'urn:restaurantpro:enrolment-code' AND subject = ?")
                .param(first.substring(0, 8)).query(String.class).single()).isEqualTo("REVOKED");
    }

    @Test
    void anEnrollmentCodeExpiresAfter24Hours() throws Exception {
        UUID terminal = registerTerminal();
        String code = issueCode(terminal).get("code").asString();

        clock.advance(Duration.ofHours(24));

        mvc.perform(enroll(code)).andExpect(status().isBadRequest());
        assertThat(column("status", terminal)).isEqualTo("REGISTERED");
    }

    @Test
    void anUnknownEnrollmentCodeIsRejectedAndTheAttemptIsLoggedWithoutTheCode(CapturedOutput output)
            throws Exception {
        mvc.perform(enroll("NOT-A-REAL-CODE").with(request -> {
            request.setRemoteAddr("192.0.2.7");
            return request;
        })).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ENROLMENT_CODE_INVALID"));

        assertThat(output.getAll())
                .contains("Device enrolment refused")
                .contains("192.0.2.7")
                .doesNotContain("NOT-A-REAL-CODE");
    }

    @Test
    void aRevokedTerminalCannotBeRevokedAgainOrGetANewCode() throws Exception {
        UUID terminal = registerTerminal();
        issueCode(terminal);

        mvc.perform(revocation(terminal, newKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        assertThat(audits("TERMINAL_REVOKED", terminal.toString())).isEqualTo(1);
        mvc.perform(revocation(terminal, newKey())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TERMINAL_STATE"));
        mvc.perform(post(TERMINALS + "/" + terminal + "/enrollment-code")
                .with(inStore(store, Permission.TERMINAL_MANAGE)).with(csrf())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TERMINAL_STATE"));
    }

    @Test
    void anotherStoresAdminCannotManageThisStoresTerminals() throws Exception {
        UUID terminal = registerTerminal();
        UUID otherStore = UUID.randomUUID();

        mvc.perform(post(TERMINALS).with(inStore(otherStore, Permission.TERMINAL_MANAGE)).with(csrf())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content(register(store, "POS9")))
                .andExpect(status().isForbidden());
        mvc.perform(get(TERMINALS).param("storeId", store.toString())
                .with(inStore(otherStore, Permission.TERMINAL_MANAGE))).andExpect(status().isForbidden());
        mvc.perform(post(TERMINALS + "/" + terminal + "/enrollment-code")
                .with(inStore(otherStore, Permission.TERMINAL_MANAGE)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(TERMINALS + "/" + terminal + "/revoke").header("Idempotency-Key", newKey())
                .with(inStore(otherStore, Permission.TERMINAL_MANAGE)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(TERMINALS + "/" + terminal + "/revoke").header("Idempotency-Key", newKey())
                .with(inStore(store, Permission.STORE_MANAGE)).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void terminalCodesAreUniqueWithinAStore() throws Exception {
        registerTerminal();

        mvc.perform(registration(store, "POS1", newKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERMINAL_CODE_TAKEN"));
    }

    @Test
    void aRegistrationRetriedWithItsKeyReplaysTheSameTerminalAndRegistersNoSecondOne() throws Exception {
        String key = newKey();
        var firstResponse = mvc.perform(registration(store, "POS1", key)).andExpect(status().isCreated())
                .andReturn().getResponse();
        String first = firstResponse.getContentAsString();

        var replayResponse = mvc.perform(registration(store, "POS1", key)).andExpect(status().isCreated())
                .andReturn().getResponse();
        String replay = replayResponse.getContentAsString();

        String id = JSON.readTree(first).get("id").asString();
        assertThat(JSON.readTree(replay).get("id").asString()).isEqualTo(id);
        assertThat(firstResponse.getHeader("Location")).isNotBlank().endsWith(id);
        assertThat(replayResponse.getHeader("Location")).isEqualTo(firstResponse.getHeader("Location"));
        assertThat(jdbc.sql("SELECT count(*) FROM organization.terminal WHERE store_id = :store")
                .param("store", store).query(Long.class).single()).isEqualTo(1);
        assertThat(audits("TERMINAL_CREATED", id)).isEqualTo(1);
    }

    @Test
    void aRegistrationKeyReusedForAnotherTerminalIsRefused() throws Exception {
        String key = newKey();
        mvc.perform(registration(store, "POS1", key)).andExpect(status().isCreated());

        mvc.perform(registration(store, "POS2", key)).andExpect(status().isUnprocessableContent());

        assertThat(jdbc.sql("SELECT count(*) FROM organization.terminal WHERE store_id = :store")
                .param("store", store).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aRevocationRetriedWithItsKeyReplaysItsAnswerAndChangesNothing() throws Exception {
        UUID terminal = registerTerminal();
        String key = newKey();
        mvc.perform(revocation(terminal, key)).andExpect(status().isOk());

        mvc.perform(revocation(terminal, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        assertThat(audits("TERMINAL_REVOKED", terminal.toString())).isEqualTo(1);
    }

    @Test
    void registrationAndRevocationNeedAnIdempotencyKey() throws Exception {
        UUID terminal = registerTerminal();

        mvc.perform(post(TERMINALS).with(inStore(store, Permission.TERMINAL_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(register(store, "POS2")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(TERMINALS + "/" + terminal + "/revoke").with(inStore(store, Permission.TERMINAL_MANAGE))
                .with(csrf())).andExpect(status().isBadRequest());

        assertThat(column("status", terminal)).isEqualTo("REGISTERED");
    }

    @Test
    void concurrentRegistrationsWithTheSameCodeGiveOneTerminalAndOneConflict() throws Exception {
        for (int round = 0; round < 10; round++) {
            String code = "R" + round;
            List<Integer> statuses = StoreHttpTest.race(() -> mvc.perform(registration(store, code, newKey()))
                    .andReturn().getResponse().getStatus());

            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(201, 409);
        }
    }

    @Test
    void aTerminalNeedsAnExistingStore() throws Exception {
        mvc.perform(post(TERMINALS).with(admin(Permission.TERMINAL_MANAGE)).with(csrf())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content(register(UUID.randomUUID(), "POS1")))
                .andExpect(status().isNotFound());
    }

    UUID registerTerminal() throws Exception {
        String json = mvc.perform(registration(store, "POS1", newKey()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(json).get("id").asString());
    }

    MockHttpServletRequestBuilder registration(UUID storeId, String code, String key) {
        return post(TERMINALS).with(inStore(storeId, Permission.TERMINAL_MANAGE)).with(csrf())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(register(storeId, code));
    }

    MockHttpServletRequestBuilder revocation(UUID terminal, String key) {
        return post(TERMINALS + "/" + terminal + "/revoke").with(inStore(store, Permission.TERMINAL_MANAGE))
                .with(csrf()).header("Idempotency-Key", key);
    }

    static String newKey() {
        return UUID.randomUUID().toString();
    }

    JsonNode issueCode(UUID terminal) throws Exception {
        return JSON.readTree(mvc.perform(post(TERMINALS + "/" + terminal + "/enrollment-code")
                        .with(inStore(store, Permission.TERMINAL_MANAGE)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsString());
    }

    /**
     * A device's enrolment (open in production, see TerminalEnrollmentSecurityTest; this module test boots without
     * the config module, under Boot's default chain: an actor with no permission and a CSRF token). Only refusals
     * are sent here: completing one needs the devices realm ({@code TerminalEnrolmentThrottleTest}).
     */
    static MockHttpServletRequestBuilder enroll(String code) {
        return post("/api/v1/pos/enrolment").with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(java.util.Map.of("code", code, "publicJwk", Devices.publicJwk())));
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
                        WHERE action = :action AND entity_type = 'Terminal' AND entity_id = :terminal
                          AND store_id = :store""")
                .param("action", action).param("terminal", terminalId).param("store", store)
                .query(Long.class).single();
    }

    static String register(UUID storeId, String code) {
        return """
                {"storeId": "%s", "code": "%s", "name": "Bar"}""".formatted(storeId, code);
    }
}
