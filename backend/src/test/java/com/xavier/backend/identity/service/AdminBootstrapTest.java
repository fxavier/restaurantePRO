package com.xavier.backend.identity.service;

import static com.xavier.backend.identity.IdentityFixtures.json;
import static com.xavier.backend.identity.IdentityFixtures.loginRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityTest;
import com.xavier.backend.organization.model.event.CompanyBootstrapped;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** D30: the first admin comes from deployment settings when the company is bootstrapped. */
@IdentityTest
@ExtendWith(OutputCaptureExtension.class)
class AdminBootstrapTest {

    static final String PASSWORD = "first admin passphrase";

    @Autowired
    AdminBootstrapService bootstrap;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @TempDir
    Path folder;

    @Test
    void theAdminMustChangeTheInitialPasswordAndThenHoldsEveryPermissionEverywhere() throws Exception {
        String username = IdentityFixtures.unique("admin");

        assertThat(bootstrap.createAdmin(settings(username, PASSWORD + "\n"))).isTrue();

        Cookie session = mvc.perform(loginRequest(username, PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn().getResponse().getCookie("SESSION");
        Cookie changed = mvc.perform(post("/api/v1/auth/password").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("currentPassword", PASSWORD, "newPassword", "the admin's own passphrase")))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
        String[] everyPermission = Arrays.stream(Permission.values()).map(Permission::authority).toArray(String[]::new);
        mvc.perform(get("/api/v1/auth/session").cookie(changed))
                .andExpect(jsonPath("$.permissions", org.hamcrest.Matchers.containsInAnyOrder(everyPermission)));
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event e JOIN identity.user_account u ON e.entity_id = u.id::text
                        WHERE e.action = 'USER_CREATED' AND e.actor_id IS NULL AND u.username = :username""")
                .param("username", username).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void runningAgainChangesNothing() throws Exception {
        String username = IdentityFixtures.unique("admin");
        bootstrap.createAdmin(settings(username, PASSWORD));
        String hash = passwordHash(username);

        assertThat(bootstrap.createAdmin(settings(username, "another initial password"))).isFalse();

        assertThat(passwordHash(username)).isEqualTo(hash);
        assertThat(jdbc.sql("SELECT count(*) FROM identity.user_account WHERE username = :u").param("u", username)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void withoutSettingsNoAdminIsCreatedAndAWarningSaysWhatToSet(CapturedOutput output) {
        assertThat(bootstrap.createAdmin(new AdminBootstrapProperties(null, null))).isFalse();
        assertThat(bootstrap.createAdmin(new AdminBootstrapProperties("someone", null))).isFalse();
        assertThat(bootstrap.createAdmin(new AdminBootstrapProperties("someone", folder.resolve("missing")))).isFalse();

        assertThat(output.getAll()).contains("RESTAURANTPRO_BOOTSTRAP_ADMIN_USERNAME");
        assertThat(jdbc.sql("SELECT count(*) FROM identity.user_account WHERE username = 'someone'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void aWeakInitialPasswordCreatesNoAdmin(CapturedOutput output) throws Exception {
        String username = IdentityFixtures.unique("admin");

        assertThat(bootstrap.createAdmin(settings(username, "short"))).isFalse();

        assertThat(output.getAll()).contains("initial password").doesNotContain("\"short\"");
    }

    @Test
    void companyBootstrappedCreatesTheConfiguredAdminOnceEvenWhenDeliveredAgain(
            @Autowired ApplicationEventPublisher events, @Autowired TransactionTemplate transaction) throws Exception {
        CompanyBootstrapped event = new CompanyBootstrapped("Test Company", "TEST-TAX-ID", "MZ", "MZN", Instant.now());

        // a restart re-delivers outstanding publications; the listener must not create a second admin
        for (int delivery = 0; delivery < 2; delivery++) {
            transaction.executeWithoutResult(tx -> events.publishEvent(event));
        }

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT count(*) FROM identity.user_account WHERE username = :u")
                        .param("u", IdentityTest.ADMIN_USERNAME).query(Long.class).single()).isEqualTo(1));
        mvc.perform(loginRequest(IdentityTest.ADMIN_USERNAME, IdentityTest.ADMIN_INITIAL_PASSWORD))
                .andExpect(status().is(org.hamcrest.Matchers.oneOf(200, 401))); // 401 if another test changed it
    }

    @Test
    void theListenerKeepsThePublicationOutstandingWhileSettingsAreMissing() {
        // so a restart with the settings fixed still creates the admin (the event is only published once)
        assertThatThrownBy(() -> bootstrap.onCompanyBootstrapped(new AdminBootstrapProperties(null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restaurantpro.bootstrap.admin");
    }

    private AdminBootstrapProperties settings(String username, String password) throws IOException {
        Path file = Files.writeString(folder.resolve(username), password);
        return new AdminBootstrapProperties(username, file);
    }

    private String passwordHash(String username) {
        return jdbc.sql("SELECT password_hash FROM identity.user_account WHERE username = :u").param("u", username)
                .query(String.class).single();
    }
}
