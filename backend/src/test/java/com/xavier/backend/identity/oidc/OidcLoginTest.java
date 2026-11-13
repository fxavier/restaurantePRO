package com.xavier.backend.identity.oidc;

import static com.xavier.backend.identity.MfaFixtures.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityTest;
import com.xavier.backend.identity.MutableClock;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * A61: a linked subject logs in through the IdP and gets the same server-side session as a password login, with
 * exactly its local authorities; the IdP's groups and roles are ignored; tokens are not kept; the session id changes.
 */
@OidcTest
class OidcLoginTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectProvider<OAuth2AuthorizedClientService> authorizedClients;

    OidcFlow flow;
    OidcLinks links;
    IdentityFixtures.User manager;
    String subject;

    @BeforeEach
    void linkedManager() throws Exception {
        flow = new OidcFlow(mvc, OidcTest.IDP);
        links = new OidcLinks(mvc, flow);
        manager = new IdentityFixtures(mvc).user(Set.of(IdentityFixtures.MANAGER), null);
        subject = OidcFlow.newSubject();
        links.link(manager, subject);
    }

    @Test
    void aLinkedSubjectGetsExactlyItsLocalAuthoritiesAndTheIdpClaimsAreIgnored() throws Exception {
        Set<String> local = permissions(passwordSession(manager));

        MockHttpServletResponse login = flow.login(subject, List.of("pwd"), Map.of(
                "groups", List.of("administrators"), "roles", List.of("ADMIN", "PERM_USER_MANAGE"),
                "preferred_username", IdentityTest.ADMIN_USERNAME));

        assertThat(login.getRedirectedUrl()).isEqualTo("/backoffice/");
        JsonNode session = body(mvc.perform(get("/api/v1/auth/session").cookie(login.getCookie("SESSION")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ACCOUNT"))
                .andExpect(jsonPath("$.userId").value(manager.id().toString()))
                .andExpect(jsonPath("$.name").value(manager.username()))
                .andExpect(jsonPath("$.mfa.state").value("NONE")));
        assertThat(local).isNotEmpty();
        assertThat(permissions(session)).isEqualTo(local);
    }

    @Test
    void theLoginIsRecordedOnTheLinkAndAudited() throws Exception {
        Instant before = clock.instant();

        flow.login(subject, List.of("pwd"), Map.of());

        assertThat(jdbc.sql("SELECT last_login_at FROM identity.external_identity WHERE subject = ?").param(subject)
                .query(java.sql.Timestamp.class).single().toInstant()).isAfterOrEqualTo(before);
        assertThat(jdbc.sql("SELECT details::text FROM audit.audit_event WHERE action = 'OIDC_LOGIN' "
                        + "AND actor_id = ? ORDER BY occurred_at DESC LIMIT 1").param(manager.id())
                .query(String.class).single())
                .contains(OidcTest.IDP.issuer()).doesNotContain(subject);
    }

    @Test
    void theSessionIdChangesAndThePreLoginSessionHoldsNothing() throws Exception {
        OidcFlow.Authorization authorization = flow.authorize(null);
        Cookie preLogin = authorization.session();

        MockHttpServletResponse login = flow.callback(authorization,
                StubIdp.Grant.of(subject, authorization.nonce(), authorization.codeChallenge()));

        Cookie loggedIn = login.getCookie("SESSION");
        assertThat(loggedIn).isNotNull();
        assertThat(loggedIn.getValue()).isNotEqualTo(preLogin.getValue());
        mvc.perform(get("/api/v1/backoffice/users").cookie(preLogin)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/session").cookie(loggedIn)).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(manager.id().toString()));
    }

    @Test
    void theAuthorizationRequestUsesPkceAndANonce() throws Exception {
        OidcFlow.Authorization authorization = flow.authorize(null);

        assertThat(authorization.params()).containsEntry("code_challenge_method", "S256")
                .containsEntry("client_id", StubIdp.CLIENT_ID)
                .containsEntry("redirect_uri", "http://localhost" + OidcRoutes.CALLBACK)
                .containsEntry("response_type", "code")
                .containsKeys("state", "nonce", "code_challenge");
        assertThat(authorization.params().get("scope")).isEqualTo("openid");
    }

    @Test
    void noTokenIsKeptInTheSessionOrTheAuthorizedClientStore() throws Exception {
        MockHttpServletResponse login = flow.login(subject, List.of("pwd"), Map.of());

        String sessionId = new String(java.util.Base64.getDecoder().decode(login.getCookie("SESSION").getValue()));
        List<String> attributes = jdbc.sql("SELECT a.attribute_name FROM shared.spring_session_attributes a "
                        + "JOIN shared.spring_session s ON s.primary_id = a.session_primary_id WHERE s.session_id = ?")
                .param(sessionId).query(String.class).list();
        assertThat(attributes).isNotEmpty().noneMatch(name -> name.toLowerCase().contains("oauth2"));
        assertThat(jdbc.sql("SELECT count(*) FROM shared.spring_session_attributes a "
                        + "JOIN shared.spring_session s ON s.primary_id = a.session_primary_id WHERE s.session_id = ? "
                        + "AND position(convert_to(?, 'UTF8') in a.attribute_bytes) > 0")
                .param(sessionId).param("stub-access-").query(Long.class).single()).isZero();
        OAuth2AuthorizedClientService store = authorizedClients.getIfAvailable();
        if (store != null) {
            assertThat((Object) store.loadAuthorizedClient(LazyClientRegistrationRepository.REGISTRATION_ID,
                    subject)).isNull();
        }
    }

    @Test
    void anUnauthenticatedApiCallIsStillA401ProblemNotARedirectToTheIdp() throws Exception {
        mvc.perform(get("/api/v1/backoffice/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    /** D144 (amended, S57 review): must-change-password is not exempt for a federated login. */
    @Test
    void anAccountThatMustChangeItsPasswordGetsTheSameRestrictedSession() throws Exception {
        IdentityFixtures.User fresh = new IdentityFixtures(mvc).newUser("initial password 1",
                Set.of(IdentityFixtures.MANAGER));
        String freshSubject = OidcFlow.newSubject();
        // linking needs a changed password; this row stands for a link made before an administrator reset it
        jdbc.sql("INSERT INTO identity.external_identity (id, account_id, issuer, subject, linked_at, "
                        + "linked_by_account_id) VALUES (?, ?, ?, ?, now(), ?)")
                .params(java.util.UUID.randomUUID(), fresh.id(), OidcTest.IDP.issuer(), freshSubject, fresh.id())
                .update();

        Cookie session = flow.login(freshSubject, List.of("pwd"), Map.of()).getCookie("SESSION");

        mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.permissions").isEmpty());
        mvc.perform(get("/api/v1/backoffice/stores").cookie(session)).andExpect(status().isForbidden());
    }

    @Test
    void theStatusShowsTheButton() throws Exception {
        mvc.perform(get(OidcRoutes.BASE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.displayName").value("Stub IdP"));
    }

    private Cookie passwordSession(IdentityFixtures.User user) throws Exception {
        return new IdentityFixtures(mvc).login(user.username(), IdentityFixtures.STRONG_PASSWORD);
    }

    private Set<String> permissions(Cookie session) throws Exception {
        return permissions(body(mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isOk())));
    }

    private static Set<String> permissions(JsonNode session) {
        Set<String> permissions = new TreeSet<>();
        session.get("permissions").forEach(p -> permissions.add(p.asString()));
        return permissions;
    }
}
