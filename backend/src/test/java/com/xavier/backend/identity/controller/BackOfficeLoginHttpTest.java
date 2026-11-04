package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.json;
import static com.xavier.backend.identity.IdentityFixtures.loginRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import com.xavier.backend.identity.IdentityFixtures.User;
import com.xavier.backend.identity.IdentityTest;
import jakarta.servlet.http.Cookie;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IdentityTest
@ExtendWith(OutputCaptureExtension.class)
class BackOfficeLoginHttpTest {

    static final String INITIAL = "initial password 1";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    IdentityFixtures fixtures;

    @BeforeEach
    void fixtures() {
        fixtures = new IdentityFixtures(mvc);
    }

    @Test
    void aPasswordLoginStartsASessionWhoseActorIsTheAccount() throws Exception {
        User user = fixtures.newUser(INITIAL, Set.of(IdentityFixtures.MANAGER));

        Cookie session = mvc.perform(loginRequest(user.username(), INITIAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.id().toString()))
                .andReturn().getResponse().getCookie("SESSION");

        mvc.perform(get("/api/v1/auth/session").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.id().toString()))
                .andExpect(jsonPath("$.employeeId").doesNotExist())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    void untilThePasswordIsChangedOnlyTheAuthRoutesWork() throws Exception {
        User user = fixtures.newUser(INITIAL, Set.of(IdentityFixtures.ADMINISTRATOR));
        Cookie session = fixtures.login(user.username(), INITIAL);

        mvc.perform(get("/api/v1/backoffice/users").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("change your password")));
        mvc.perform(get("/api/v1/auth/session").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", empty()));
    }

    @Test
    void changingThePasswordClearsMustChangePasswordAndGrantsTheRoles() throws Exception {
        User user = fixtures.newUser(INITIAL, Set.of(IdentityFixtures.ADMINISTRATOR));
        Cookie session = fixtures.login(user.username(), INITIAL);

        Cookie changed = mvc.perform(post("/api/v1/auth/password").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("currentPassword", INITIAL, "newPassword", "a much better passphrase")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andReturn().getResponse().getCookie("SESSION");

        Cookie current = changed != null ? changed : session;
        mvc.perform(get("/api/v1/backoffice/users").cookie(current)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT must_change_password FROM identity.user_account WHERE id = :id")
                .param("id", user.id()).query(Boolean.class).single()).isFalse();
        assertThat(audits("USER_PASSWORD_CHANGED", user.id())).isEqualTo(1);
        mvc.perform(loginRequest(user.username(), INITIAL)).andExpect(status().isUnauthorized());
        mvc.perform(loginRequest(user.username(), "a much better passphrase")).andExpect(status().isOk());
    }

    @Test
    void aNewPasswordNeedsTwelveCharactersAndTheRightCurrentOne() throws Exception {
        User user = fixtures.newUser(INITIAL, Set.of());
        Cookie session = fixtures.login(user.username(), INITIAL);

        mvc.perform(post("/api/v1/auth/password").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("currentPassword", INITIAL, "newPassword", "short pass")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("12")));
        mvc.perform(post("/api/v1/auth/password").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("currentPassword", "not the current one", "newPassword", "long enough pass")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aWrongPasswordGetsOneGeneric401AndAWarnLogWithoutTheSecret(CapturedOutput output) throws Exception {
        User user = fixtures.newUser(INITIAL, Set.of());

        String wrong = mvc.perform(loginRequest(user.username(), "Wr0ng-Secret-Value"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(loginRequest("nobody-" + UUID.randomUUID(), "Wr0ng-Secret-Value"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        assertThat(wrong).isEqualTo(unknown);
        assertThat(output.getAll()).contains("login refused").doesNotContain("Wr0ng-Secret-Value");
    }

    @Test
    void anInactiveAccountCannotLogIn() throws Exception {
        User user = fixtures.newUser(INITIAL, Set.of());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/backoffice/users/" + user.id()).with(IdentityFixtures.admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("roleIds", Set.of(), "active", false, "version", 0)))
                .andExpect(status().isOk());

        mvc.perform(loginRequest(user.username(), INITIAL)).andExpect(status().isUnauthorized());
    }

    @Test
    void loggingInAgainIssuesANewSessionIdAndTheOldOneStopsWorking() throws Exception {
        User user = fixtures.user(Set.of(IdentityFixtures.MANAGER), null);
        Cookie first = fixtures.login(user.username(), IdentityFixtures.STRONG_PASSWORD);

        Cookie second = mvc.perform(loginRequest(user.username(), IdentityFixtures.STRONG_PASSWORD).cookie(first))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");

        assertThat(second.getValue()).isNotEqualTo(first.getValue());
        mvc.perform(get("/api/v1/auth/session").cookie(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/session").cookie(second)).andExpect(status().isOk());
    }

    @Test
    void installationWideRolesGiveGlobalAuthoritiesAndAssignmentsGiveStoreScopedOnes() throws Exception {
        UUID storeA = fixtures.store();
        Employee employee = fixtures.employee("1234", storeA, IdentityFixtures.WAITER);
        User user = fixtures.user(Set.of(IdentityFixtures.ADMINISTRATOR), employee.id());

        Cookie session = fixtures.login(user.username(), IdentityFixtures.STRONG_PASSWORD);

        mvc.perform(get("/api/v1/auth/session").cookie(session))
                .andExpect(jsonPath("$.permissions", org.hamcrest.Matchers.hasItems(
                        Permission.SYSTEM_MONITOR.authority(), Permission.USER_MANAGE.authority(),
                        Permission.DINING_VIEW.authority(storeA), Permission.CATALOG_VIEW.authority(storeA))));
    }

    @Test
    void anAssignedEmployeeAccountHoldsOnlyItsStoreAuthorities() throws Exception {
        UUID storeA = fixtures.store();
        Employee employee = fixtures.employee("1234", storeA, IdentityFixtures.WAITER);
        User user = fixtures.user(Set.of(), employee.id());

        Cookie session = fixtures.login(user.username(), IdentityFixtures.STRONG_PASSWORD);

        mvc.perform(get("/api/v1/auth/session").cookie(session))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(
                        Permission.DINING_VIEW.authority(storeA), Permission.CATALOG_VIEW.authority(storeA))));
    }

    @Test
    void deactivatingAnAccountEndsItsOpenSessions() throws Exception {
        User user = fixtures.newUser(INITIAL, Set.of());
        Cookie session = fixtures.login(user.username(), INITIAL);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/backoffice/users/" + user.id()).with(IdentityFixtures.admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("roleIds", Set.of(), "active", false, "version", 0)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void anInactiveAccountCannotChangeItsPassword() throws Exception {
        User user = fixtures.newUser(INITIAL, Set.of());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/backoffice/users/" + user.id()).with(IdentityFixtures.admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("roleIds", Set.of(), "active", false, "version", 0)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/password").with(IdentityFixtures.asAccount(user.id())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("currentPassword", INITIAL, "newPassword", "a much better passphrase")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingThePasswordEndsTheAccountsOtherSessions() throws Exception {
        User user = fixtures.newUser(INITIAL, Set.of());
        Cookie here = fixtures.login(user.username(), INITIAL);
        Cookie elsewhere = fixtures.login(user.username(), INITIAL);

        Cookie changed = mvc.perform(post("/api/v1/auth/password").cookie(here).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("currentPassword", INITIAL, "newPassword", "a much better passphrase")))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");

        mvc.perform(get("/api/v1/auth/session").cookie(elsewhere)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/session").cookie(changed != null ? changed : here))
                .andExpect(status().isOk());
    }

    @Test
    void loggingOutEndsTheSession() throws Exception {
        User user = fixtures.newUser(INITIAL, Set.of());
        Cookie session = fixtures.login(user.username(), INITIAL);

        mvc.perform(post("/api/v1/auth/logout").cookie(session).with(csrf())).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("""
                        SELECT action FROM audit.audit_event WHERE entity_id = :id AND actor_id = :actor
                          AND action IN ('USER_LOGGED_IN', 'USER_LOGGED_OUT')""")
                .param("id", user.id().toString()).param("actor", user.id()).query(String.class).list())
                .containsExactlyInAnyOrder("USER_LOGGED_IN", "USER_LOGGED_OUT");
    }

    @Test
    void theSessionRouteNeedsALogin() throws Exception {
        mvc.perform(get("/api/v1/auth/session")).andExpect(status().isUnauthorized());
    }

    private long audits(String action, UUID entityId) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = :action AND entity_id = :id")
                .param("action", action).param("id", entityId.toString()).query(Long.class).single();
    }
}
