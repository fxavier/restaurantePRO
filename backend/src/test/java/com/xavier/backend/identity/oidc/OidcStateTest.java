package com.xavier.backend.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spring's defaults, pinned (D144): the callback must carry the {@code state} of an authorization this browser
 * session started, and the ID token the {@code nonce} of that authorization; otherwise nothing logs in.
 */
@OidcTest
class OidcStateTest {

    @Autowired
    MockMvc mvc;

    OidcFlow flow;
    String subject;

    @BeforeEach
    void linkedUser() throws Exception {
        flow = new OidcFlow(mvc, OidcTest.IDP);
        IdentityFixtures.User user = new IdentityFixtures(mvc).user(Set.of(IdentityFixtures.MANAGER), null);
        subject = OidcFlow.newSubject();
        new OidcLinks(mvc, flow).link(user, subject);
    }

    @Test
    void aCallbackWithTheStateOfAnotherSessionIsRefusedAndTheCodeNeverRedeemed() throws Exception {
        OidcFlow.Authorization victim = flow.authorize(null);
        OidcFlow.Authorization attacker = flow.authorize(null);
        String code = "code-" + UUID.randomUUID();
        OidcTest.IDP.issue(code, StubIdp.Grant.of(subject, attacker.nonce(), attacker.codeChallenge()));

        MockHttpServletResponse callback = flow.callback(victim.session(), code, attacker.state());

        assertRefused(callback);
        assertThat(OidcTest.IDP.redeemed(code)).isFalse();
    }

    @Test
    void aCallbackWithoutAnyAuthorizationInTheSessionIsRefused() throws Exception {
        OidcFlow.Authorization started = flow.authorize(null);
        String code = "code-" + UUID.randomUUID();
        OidcTest.IDP.issue(code, StubIdp.Grant.of(subject, started.nonce(), started.codeChallenge()));

        MockHttpServletResponse callback = flow.callback(null, code, started.state());

        assertRefused(callback);
        assertThat(OidcTest.IDP.redeemed(code)).isFalse();
    }

    @Test
    void anIdTokenWithAnotherNonceIsRefused() throws Exception {
        OidcFlow.Authorization authorization = flow.authorize(null);

        MockHttpServletResponse callback = flow.callback(authorization,
                StubIdp.Grant.of(subject, authorization.nonce(), authorization.codeChallenge())
                        .withNonce("replayed-nonce"));

        assertRefused(callback);
    }

    @Test
    void anIdTokenForAnotherClientIsRefused() throws Exception {
        OidcFlow.Authorization authorization = flow.authorize(null);
        StubIdp.Grant grant = StubIdp.Grant.of(subject, authorization.nonce(), authorization.codeChallenge());

        MockHttpServletResponse callback = flow.callback(authorization, new StubIdp.Grant(grant.subject(),
                grant.nonce(), grant.codeChallenge(), grant.amr(), grant.extraClaims(), "another-client"));

        assertRefused(callback);
    }

    @Test
    void theAuthorizationIsUsedOnce() throws Exception {
        OidcFlow.Authorization authorization = flow.authorize(null);
        flow.callback(authorization, StubIdp.Grant.of(subject, authorization.nonce(), authorization.codeChallenge()));
        String code = "code-" + UUID.randomUUID();
        OidcTest.IDP.issue(code, StubIdp.Grant.of(subject, authorization.nonce(), authorization.codeChallenge()));

        MockHttpServletResponse replay = flow.callback(authorization.session(), code, authorization.state());

        assertThat(replay.getRedirectedUrl()).isEqualTo("/backoffice/login?error=oidc");
        assertThat(OidcTest.IDP.redeemed(code)).isFalse();
    }

    /** S57 review: a token endpoint that accepts and never answers fails the callback within the read timeout. */
    @Test
    void aBlackHoleTokenEndpointFailsTheCallbackQuickly() throws Exception {
        OidcFlow.Authorization authorization = flow.authorize(null);
        OidcTest.IDP.blackHoleToken();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            long started = System.nanoTime();
            MockHttpServletResponse callback = pool.submit(() -> flow.callback(authorization,
                            StubIdp.Grant.of(subject, authorization.nonce(), authorization.codeChallenge())))
                    .get(20, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(java.time.Duration.ofSeconds(10));
            assertRefused(callback);
        } finally {
            OidcTest.IDP.answer();
            pool.shutdownNow();
        }
    }

    private void assertRefused(MockHttpServletResponse callback) throws Exception {
        assertThat(callback.getRedirectedUrl()).isEqualTo("/backoffice/login?error=oidc");
        if (callback.getCookie("SESSION") != null) {
            mvc.perform(get("/api/v1/auth/session").cookie(callback.getCookie("SESSION")))
                    .andExpect(status().isUnauthorized());
        }
    }
}
