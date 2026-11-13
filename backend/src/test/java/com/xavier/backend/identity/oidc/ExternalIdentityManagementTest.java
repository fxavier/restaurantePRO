package com.xavier.backend.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.MfaFixtures;
import com.xavier.backend.identity.MutableClock;
import com.xavier.backend.identity.SwitchableMfaPolicy;
import com.xavier.backend.identity.mfa.MfaMode;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** D144: the owner reads and removes their link; an administrator ({@code USER_MANAGE}) reads and removes any. */
@OidcTest
class ExternalIdentityManagementTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    SwitchableMfaPolicy policy;

    @Autowired
    JdbcClient jdbc;

    OidcFlow flow;
    IdentityFixtures identity;
    IdentityFixtures.User user;
    String subject;

    @BeforeEach
    void linkedUser() throws Exception {
        flow = new OidcFlow(mvc, OidcTest.IDP);
        identity = new IdentityFixtures(mvc);
        user = identity.user(Set.of(IdentityFixtures.MANAGER), null);
        subject = OidcFlow.newSubject();
        new OidcLinks(mvc, flow).link(user, subject);
    }

    @AfterEach
    void reset() {
        policy.reset();
    }

    @Test
    void theOwnerReadsAndRemovesTheLinkAndKeepsThePassword() throws Exception {
        Cookie session = identity.login(user.username(), IdentityFixtures.STRONG_PASSWORD);
        mvc.perform(get(OidcRoutes.LINK).cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(user.id().toString()))
                .andExpect(jsonPath("$.issuer").value(OidcTest.IDP.issuer()))
                .andExpect(jsonPath("$.subject").value(subject))
                .andExpect(jsonPath("$.linkedByAccountId").value(user.id().toString()));

        mvc.perform(delete(OidcRoutes.LINK).cookie(session)).andExpect(status().isForbidden());
        mvc.perform(delete(OidcRoutes.LINK).cookie(session).with(csrf())).andExpect(status().isNoContent());

        assertThat(OidcAudit.details(jdbc, "OIDC_UNLINKED", user.id().toString())).hasSize(1);
        mvc.perform(get(OidcRoutes.LINK).cookie(session)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXTERNAL_IDENTITY_NOT_FOUND"));
        assertThat(flow.login(subject, List.of("pwd"), Map.of()).getRedirectedUrl())
                .isEqualTo("/backoffice/login?error=oidc");
        identity.login(user.username(), IdentityFixtures.STRONG_PASSWORD);
    }

    @Test
    void aPendingSessionCannotReadOrRemoveTheLink() throws Exception {
        MfaFixtures mfa = new MfaFixtures(mvc, clock);
        MfaFixtures.Enrolled enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.MANAGER));
        new OidcLinks(mvc, flow).completeLink(enrolled.session(), OidcFlow.newSubject());
        policy.set(MfaMode.ALL_BACKOFFICE);
        Cookie pending = mvc.perform(IdentityFixtures.loginRequest(enrolled.user().username(),
                IdentityFixtures.STRONG_PASSWORD)).andReturn().getResponse().getCookie("SESSION");

        mvc.perform(get(OidcRoutes.LINK).cookie(pending)).andExpect(status().isForbidden());
        mvc.perform(delete(OidcRoutes.LINK).cookie(pending).with(csrf())).andExpect(status().isForbidden());

        assertThat(jdbc.sql("SELECT count(*) FROM identity.external_identity WHERE account_id = ?")
                .param(enrolled.user().id()).query(Long.class).single()).isOne();
    }

    @Test
    void anAdministratorReadsAndRemovesAnAccountsLink() throws Exception {
        String path = "/api/v1/backoffice/users/" + user.id() + "/external-identity";
        mvc.perform(get(path).with(IdentityFixtures.admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(subject));

        mvc.perform(delete(path).with(IdentityFixtures.admin()).with(csrf())).andExpect(status().isNoContent());

        mvc.perform(get(path).with(IdentityFixtures.admin())).andExpect(status().isNotFound());
        mvc.perform(delete(path).with(IdentityFixtures.admin()).with(csrf())).andExpect(status().isNotFound());
        assertThat(OidcAudit.details(jdbc, "OIDC_UNLINKED", user.id().toString())).hasSize(1);
    }

    @Test
    void withoutUserManageAnAccountsLinkIsNeitherReadNorRemoved() throws Exception {
        String path = "/api/v1/backoffice/users/" + user.id() + "/external-identity";
        var reader = IdentityFixtures.as(Set.of(Permission.ROLE_MANAGE.authority(), Permission.STORE_MANAGE.authority()));

        mvc.perform(get(path).with(reader)).andExpect(status().isForbidden());
        mvc.perform(delete(path).with(reader).with(csrf())).andExpect(status().isForbidden());

        mvc.perform(get(path).with(IdentityFixtures.admin())).andExpect(status().isOk());
    }
}
