package com.xavier.backend.identity.oidc;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static com.xavier.backend.identity.IdentityFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.MfaFixtures;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/** A61: an inactive account and a support account are refused like a wrong password, even when linked. */
@OidcTest
class OidcInactiveAndSupportRefusedTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    OidcFlow flow;

    @BeforeEach
    void flow() {
        flow = new OidcFlow(mvc, OidcTest.IDP);
    }

    @Test
    void aLinkedButInactiveAccountIsRefused() throws Exception {
        IdentityFixtures.User user = new IdentityFixtures(mvc).user(Set.of(IdentityFixtures.MANAGER), null);
        String subject = OidcFlow.newSubject();
        new OidcLinks(mvc, flow).link(user, subject);
        deactivate(user.id());

        MockHttpServletResponse callback = flow.login(subject, List.of("pwd"), Map.of());

        assertRefused(callback, user.id(), "INACTIVE");
    }

    @Test
    void aLinkedSupportAccountIsRefused() throws Exception {
        UUID support = IdentityFixtures.id(mvc.perform(post("/api/v1/backoffice/users").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", IdentityFixtures.unique("sup"), "initialPassword",
                                "initial password 1", "roleIds", Set.of(IdentityFixtures.ADMINISTRATOR),
                                "support", true)))
                .andExpect(status().isCreated()).andReturn().getResponse());
        String subject = OidcFlow.newSubject();
        // linking refuses a support account (422); this row stands for a link the API can never make
        jdbc.sql("INSERT INTO identity.external_identity (id, account_id, issuer, subject, linked_at, "
                        + "linked_by_account_id) VALUES (?, ?, ?, ?, now(), ?)")
                .params(UUID.randomUUID(), support, OidcTest.IDP.issuer(), subject, support).update();

        MockHttpServletResponse callback = flow.login(subject, List.of("pwd"), Map.of());

        assertRefused(callback, support, "SUPPORT_ACCOUNT");
    }

    private void assertRefused(MockHttpServletResponse callback, UUID account, String reason) throws Exception {
        assertThat(callback.getRedirectedUrl()).isEqualTo("/backoffice/login?error=oidc");
        assertThat(OidcAudit.details(jdbc, "OIDC_LOGIN_REFUSED", account.toString()))
                .singleElement().asString().contains(reason).contains(OidcTest.IDP.issuer());
        assertThat(OidcAudit.details(jdbc, "OIDC_LOGIN", account.toString())).isEmpty();
        if (callback.getCookie("SESSION") != null) {
            mvc.perform(get("/api/v1/auth/session").cookie(callback.getCookie("SESSION")))
                    .andExpect(status().isUnauthorized());
        }
    }

    private void deactivate(UUID user) throws Exception {
        JsonNode current = MfaFixtures.body(mvc.perform(get("/api/v1/backoffice/users/" + user).with(admin()))
                .andExpect(status().isOk()));
        mvc.perform(put("/api/v1/backoffice/users/" + user).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("roleIds", Set.of(IdentityFixtures.MANAGER), "active", false,
                                "version", current.get("version").asLong())))
                .andExpect(status().isOk());
    }
}
