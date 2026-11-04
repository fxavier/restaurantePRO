package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static com.xavier.backend.identity.IdentityFixtures.json;
import static com.xavier.backend.identity.IdentityFixtures.unique;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import com.xavier.backend.identity.IdentityFixtures.User;
import com.xavier.backend.identity.IdentityTest;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Authorities are resolved at login, so taking a permission out of a role ends the sessions that hold the role
 * (installation-wide holders and employees assigned with it, POS and linked BackOffice accounts). A custom role
 * stands in for a seeded one such as Gerente: editing a seeded role would leak into other tests.
 */
@IdentityTest
class RoleSessionsHttpTest {

    static final String PIN = "5821";

    @Autowired
    MockMvc mvc;

    IdentityFixtures fixtures;
    UUID role;
    Cookie backOffice;
    Cookie pos;

    @BeforeEach
    void sessionsHoldingTheRole() throws Exception {
        fixtures = new IdentityFixtures(mvc);
        role = IdentityFixtures.id(mvc.perform(post("/api/v1/backoffice/roles").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", unique("Publisher"), "permissions",
                                List.of("CATALOG_PUBLISH", "CATALOG_VIEW"))))
                .andExpect(status().isCreated()).andReturn().getResponse());
        User user = fixtures.user(Set.of(role), null);
        backOffice = fixtures.login(user.username(), IdentityFixtures.STRONG_PASSWORD);
        UUID store = fixtures.store();
        Employee employee = fixtures.employee(PIN, store, role);
        pos = fixtures.posLogin(fixtures.enrolledTerminal(store), employee, PIN);
    }

    @Test
    void removingAPermissionFromARoleEndsTheSessionsHoldingIt() throws Exception {
        redefine(List.of("CATALOG_VIEW"));

        mvc.perform(get("/api/v1/auth/session").cookie(backOffice)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/session").cookie(pos)).andExpect(status().isUnauthorized());
    }

    @Test
    void addingAPermissionLeavesTheSessionsAlive() throws Exception {
        redefine(List.of("CATALOG_PUBLISH", "CATALOG_VIEW", "DINING_VIEW"));

        mvc.perform(get("/api/v1/auth/session").cookie(backOffice)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/session").cookie(pos)).andExpect(status().isOk());
    }

    private void redefine(List<String> permissions) throws Exception {
        long version = IdentityFixtures.JSON.readTree(mvc.perform(get("/api/v1/backoffice/roles/" + role)
                .with(admin())).andReturn().getResponse().getContentAsString()).get("version").asLong();
        mvc.perform(put("/api/v1/backoffice/roles/" + role).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", unique("Publisher"), "permissions", permissions, "version", version)))
                .andExpect(status().isOk());
    }
}
