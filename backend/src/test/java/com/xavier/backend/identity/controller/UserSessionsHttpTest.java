package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static com.xavier.backend.identity.IdentityFixtures.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.User;
import com.xavier.backend.identity.IdentityTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IdentityTest
class UserSessionsHttpTest {

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
    void anAdminListsAUsersSessionsWithoutLearningTheSessionIds() throws Exception {
        User user = fixtures.newUser("initial password 1", Set.of());
        Cookie first = fixtures.login(user.username(), "initial password 1");
        Cookie second = fixtures.login(user.username(), "initial password 1");

        String body = mvc.perform(get(sessionsOf(user)).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(sessionId(first)).doesNotContain(sessionId(second));
    }

    @Test
    void revokingOneSessionEndsOnlyThatSessionAndIsAudited() throws Exception {
        User user = fixtures.newUser("initial password 1", Set.of());
        Cookie revoked = fixtures.login(user.username(), "initial password 1");
        Cookie kept = fixtures.login(user.username(), "initial password 1");
        JsonNode sessions = IdentityFixtures.JSON.readTree(mvc.perform(get(sessionsOf(user)).with(admin()))
                .andReturn().getResponse().getContentAsString());
        String handle = handleOfOtherThan(sessions, kept);

        mvc.perform(delete(sessionsOf(user) + "/" + handle).with(admin()).with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/session").cookie(revoked)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/session").cookie(kept)).andExpect(status().isOk());
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event WHERE action = 'USER_SESSION_REVOKED' AND entity_id = :id""")
                .param("id", user.id().toString()).query(Long.class).single()).isEqualTo(1);
        mvc.perform(delete(sessionsOf(user) + "/" + handle).with(admin()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sessionsAreManagedWithAnInstallationWideUserManage() throws Exception {
        User user = fixtures.newUser("initial password 1", Set.of());

        mvc.perform(get(sessionsOf(user)).with(as(Set.of(Permission.EMPLOYEE_MANAGE.authority()))))
                .andExpect(status().isForbidden());
    }

    /** The handle of the session that is not {@code kept}: handles are opaque, so ask which one is current. */
    private String handleOfOtherThan(JsonNode sessions, Cookie kept) throws Exception {
        String keptHandle = IdentityFixtures.JSON.readTree(mvc.perform(get("/api/v1/auth/session").cookie(kept))
                .andReturn().getResponse().getContentAsString()).get("sessionHandle").asString();
        for (JsonNode session : sessions) {
            if (!session.get("id").asString().equals(keptHandle)) {
                return session.get("id").asString();
            }
        }
        throw new AssertionError("no other session in " + sessions);
    }

    private static String sessionsOf(User user) {
        return "/api/v1/backoffice/users/" + user.id() + "/sessions";
    }

    private static String sessionId(Cookie cookie) {
        return new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
    }
}
