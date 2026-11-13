package com.xavier.backend.identity.oidc;

import static com.xavier.backend.identity.MfaFixtures.from;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.MfaFixtures;
import com.xavier.backend.identity.MfaTest;
import com.xavier.backend.identity.MutableClock;
import com.xavier.backend.identity.SwitchableMfaPolicy;
import com.xavier.backend.identity.mfa.MfaMode;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D144 linking: only the account's own, finished session may link, with a fresh second factor while MFA is not OFF.
 * {@code /api/v1/auth/**} is open to a password-only {@code MFA_PENDING} session by design, so these refusals are
 * what stops a stolen password from attaching the attacker's IdP identity (which would then log in without the
 * second factor's prompt being met by the owner).
 */
@OidcTest
class OidcLinkFlowTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    SwitchableMfaPolicy policy;

    @Autowired
    JdbcClient jdbc;

    OidcFlow flow;
    OidcLinks links;
    MfaFixtures mfa;
    IdentityFixtures identity;

    @BeforeEach
    void setUp() {
        flow = new OidcFlow(mvc, OidcTest.IDP);
        links = new OidcLinks(mvc, flow);
        mfa = new MfaFixtures(mvc, clock);
        identity = new IdentityFixtures(mvc);
    }

    @AfterEach
    void reset() {
        policy.reset();
    }

    @Test
    void withMfaOffTheSessionsOwnAccountIsLinkedAndStaysLoggedIn() throws Exception {
        IdentityFixtures.User user = identity.user(Set.of(IdentityFixtures.MANAGER), null);
        Cookie session = identity.login(user.username(), IdentityFixtures.STRONG_PASSWORD);
        String subject = OidcFlow.newSubject();

        MockHttpServletResponse callback = links.completeLink(session, subject);

        assertThat(callback.getRedirectedUrl()).isEqualTo("/backoffice/");
        assertThat(linkedAccount(subject)).isEqualTo(user.id());
        assertThat(OidcAudit.details(jdbc, "OIDC_LINKED", user.id().toString())).singleElement().asString()
                .contains(OidcTest.IDP.issuer()).doesNotContain(subject);
        Cookie after = callback.getCookie("SESSION") != null ? callback.getCookie("SESSION") : session;
        mvc.perform(get("/api/v1/auth/session").cookie(after)).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.id().toString()))
                .andExpect(jsonPath("$.permissions").isNotEmpty());
        // P56: the password stays a way in
        identity.login(user.username(), IdentityFixtures.STRONG_PASSWORD);
    }

    @Test
    void aPendingPasswordOnlySessionCannotLinkAndItsCallbackLinksNothing() throws Exception {
        MfaFixtures.Enrolled enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.MANAGER));
        policy.set(MfaMode.ALL_BACKOFFICE);
        Cookie pending = mvc.perform(IdentityFixtures.loginRequest(enrolled.user().username(),
                        IdentityFixtures.STRONG_PASSWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa.state").value("PENDING")).andReturn().getResponse().getCookie("SESSION");

        mvc.perform(OidcLinks.startLink(pending)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_STEP_UP_REQUIRED"));

        String subject = OidcFlow.newSubject();
        OidcFlow.Authorization authorization = flow.authorize(pending);
        MockHttpServletResponse callback = flow.callback(authorization,
                StubIdp.Grant.of(subject, authorization.nonce(), authorization.codeChallenge()));
        assertThat(callback.getRedirectedUrl()).isEqualTo("/backoffice/login?error=oidc");
        assertThat(linkedAccount(subject)).isNull();
    }

    @Test
    void aPendingOrEnrollingSessionCannotLinkEvenOnceTheModeIsOff() throws Exception {
        MfaFixtures.Enrolled enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.MANAGER));
        IdentityFixtures.User unenrolled = identity.user(Set.of(IdentityFixtures.MANAGER), null);
        policy.set(MfaMode.ALL_BACKOFFICE);
        Cookie pending = mvc.perform(IdentityFixtures.loginRequest(enrolled.user().username(),
                IdentityFixtures.STRONG_PASSWORD)).andReturn().getResponse().getCookie("SESSION");
        Cookie enrolling = mvc.perform(IdentityFixtures.loginRequest(unenrolled.username(),
                IdentityFixtures.STRONG_PASSWORD)).andReturn().getResponse().getCookie("SESSION");
        // the operator switches MFA off and restarts; stored sessions keep their state
        policy.set(MfaMode.OFF);

        mvc.perform(OidcLinks.startLink(pending)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_STEP_UP_REQUIRED"));
        mvc.perform(OidcLinks.startLink(enrolling)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLMENT_REQUIRED"));
    }

    @Test
    void aLinkRequestDoesNotSurviveALoginAsSomeoneElseInTheSameBrowser() throws Exception {
        IdentityFixtures.User asked = identity.user(Set.of(IdentityFixtures.MANAGER), null);
        IdentityFixtures.User other = identity.user(Set.of(IdentityFixtures.MANAGER), null);
        Cookie session = identity.login(asked.username(), IdentityFixtures.STRONG_PASSWORD);
        mvc.perform(OidcLinks.startLink(session)).andExpect(status().isOk());
        Cookie switched = mvc.perform(IdentityFixtures.loginRequest(other.username(), IdentityFixtures.STRONG_PASSWORD)
                .cookie(session)).andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
        String subject = OidcFlow.newSubject();

        OidcFlow.Authorization authorization = flow.authorize(switched);
        MockHttpServletResponse callback = flow.callback(authorization,
                StubIdp.Grant.of(subject, authorization.nonce(), authorization.codeChallenge()));

        assertThat(callback.getRedirectedUrl()).isEqualTo("/backoffice/login?error=oidc");
        assertThat(linkedAccount(subject)).isNull();
    }

    /** S57 review: a password-only re-login as the same account in the same browser cannot finish the owner's link. */
    @Test
    void aLinkRequestDoesNotSurviveAPendingReLoginAsTheSameAccount() throws Exception {
        MfaFixtures.Enrolled enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.MANAGER));
        policy.set(MfaMode.ALL_BACKOFFICE);
        mvc.perform(OidcLinks.startLink(enrolled.session())).andExpect(status().isOk());
        Cookie pending = mvc.perform(IdentityFixtures.loginRequest(enrolled.user().username(),
                        IdentityFixtures.STRONG_PASSWORD).cookie(enrolled.session()))
                .andExpect(jsonPath("$.mfa.state").value("PENDING")).andReturn().getResponse().getCookie("SESSION");

        assertNothingLinkedBy(pending);
    }

    @Test
    void aLinkRequestDoesNotSurviveALanReLoginWithoutAFreshFactor() throws Exception {
        MfaFixtures.Enrolled enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.MANAGER));
        policy.set(MfaMode.SENSITIVE);
        mvc.perform(OidcLinks.startLink(enrolled.session())).andExpect(status().isOk());
        Cookie lan = mvc.perform(IdentityFixtures.loginRequest(enrolled.user().username(),
                        IdentityFixtures.STRONG_PASSWORD).cookie(enrolled.session()).with(from(MfaTest.LAN_ADDRESS)))
                .andExpect(jsonPath("$.mfa.state").value("NONE")).andReturn().getResponse().getCookie("SESSION");

        assertNothingLinkedBy(lan);
    }

    /** A link request belongs to the login that made it, even when the new login could have asked itself. */
    @Test
    void aLinkRequestDoesNotSurviveAnyNewLogin() throws Exception {
        IdentityFixtures.User user = identity.user(Set.of(IdentityFixtures.MANAGER), null);
        Cookie session = identity.login(user.username(), IdentityFixtures.STRONG_PASSWORD);
        mvc.perform(OidcLinks.startLink(session)).andExpect(status().isOk());
        Cookie again = mvc.perform(IdentityFixtures.loginRequest(user.username(), IdentityFixtures.STRONG_PASSWORD)
                .cookie(session)).andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");

        assertNothingLinkedBy(again);
    }

    /** The callback checks the session again: a second factor that went stale meanwhile does not link. */
    @Test
    void aSecondFactorThatWentStaleBeforeTheCallbackDoesNotLink() throws Exception {
        MfaFixtures.Enrolled enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.MANAGER));
        policy.set(MfaMode.SENSITIVE);
        clock.advance(policy.stepUpMaxAge().minus(Duration.ofMinutes(1)));
        mvc.perform(OidcLinks.startLink(enrolled.session())).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(2));
        String subject = OidcFlow.newSubject();

        OidcFlow.Authorization authorization = flow.authorize(enrolled.session());
        MockHttpServletResponse callback = flow.callback(authorization,
                StubIdp.Grant.of(subject, authorization.nonce(), authorization.codeChallenge()));

        assertThat(callback.getRedirectedUrl()).isEqualTo("/backoffice/?error=oidc-link");
        assertThat(linkedAccount(subject)).isNull();
    }

    /** S57 review: a failed callback (here a foreign state) uses the request up; a later good one does not link. */
    @Test
    void aFailedCallbackDropsTheLinkRequest() throws Exception {
        IdentityFixtures.User user = identity.user(Set.of(IdentityFixtures.MANAGER), null);
        Cookie session = identity.login(user.username(), IdentityFixtures.STRONG_PASSWORD);
        mvc.perform(OidcLinks.startLink(session)).andExpect(status().isOk());
        OidcFlow.Authorization first = flow.authorize(session);

        MockHttpServletResponse failed = flow.callback(first.session(), "code-" + UUID.randomUUID(), "not-the-state");
        assertThat(failed.getRedirectedUrl()).isEqualTo("/backoffice/login?error=oidc");

        assertNothingLinkedBy(first.session());
    }

    /** The IdP round trip on {@code session}: it is a login attempt of an unlinked identity, refused, and links nothing. */
    private void assertNothingLinkedBy(Cookie session) throws Exception {
        String subject = OidcFlow.newSubject();
        OidcFlow.Authorization authorization = flow.authorize(session);
        MockHttpServletResponse callback = flow.callback(authorization,
                StubIdp.Grant.of(subject, authorization.nonce(), authorization.codeChallenge()));
        assertThat(callback.getRedirectedUrl()).isEqualTo("/backoffice/login?error=oidc");
        assertThat(linkedAccount(subject)).isNull();
    }

    @Test
    void aPosSessionCannotLink() throws Exception {
        com.xavier.backend.access.Actor employee = com.xavier.backend.access.Actor.employee(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Set.of());
        mvc.perform(post(OidcRoutes.LINK).with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.authentication(org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken.authenticated(employee, null, java.util.List.of())))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anEnrolmentRequiredSessionCannotLink() throws Exception {
        IdentityFixtures.User user = identity.user(Set.of(IdentityFixtures.MANAGER), null);
        policy.set(MfaMode.ALL_BACKOFFICE);
        Cookie enrolling = mvc.perform(IdentityFixtures.loginRequest(user.username(), IdentityFixtures.STRONG_PASSWORD)
                        .with(from(MfaTest.LAN_ADDRESS))).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa.state").value("ENROLMENT_REQUIRED")).andReturn().getResponse()
                .getCookie("SESSION");

        mvc.perform(OidcLinks.startLink(enrolling)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLMENT_REQUIRED"));
    }

    @Test
    void withMfaOnASessionThatNeverProvedTheFactorMustStepUpFirst() throws Exception {
        MfaFixtures.Enrolled enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.MANAGER));
        policy.set(MfaMode.SENSITIVE);
        Cookie lanSession = mfa.login(enrolled.user().username(), MfaTest.LAN_ADDRESS);
        String subject = OidcFlow.newSubject();

        mvc.perform(OidcLinks.startLink(lanSession)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_STEP_UP_REQUIRED"))
                .andExpect(jsonPath("$.stepUpUri").value("/api/v1/auth/mfa/step-up"));

        mvc.perform(MfaFixtures.stepUp(lanSession, mfa.nextCode(enrolled.secret()))).andExpect(status().isOk());
        MockHttpServletResponse callback = links.completeLink(lanSession, subject);
        assertThat(callback.getRedirectedUrl()).isEqualTo("/backoffice/");
        assertThat(linkedAccount(subject)).isEqualTo(enrolled.user().id());
    }

    @Test
    void aSecondFactorOlderThanTheStepUpWindowIsNotFresh() throws Exception {
        MfaFixtures.Enrolled enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.MANAGER));
        policy.set(MfaMode.SENSITIVE);
        clock.advance(policy.stepUpMaxAge().plus(Duration.ofSeconds(1)));

        mvc.perform(OidcLinks.startLink(enrolled.session())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_STEP_UP_REQUIRED"));
    }

    @Test
    void aFreshSecondFactorLinks() throws Exception {
        MfaFixtures.Enrolled enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.MANAGER));
        policy.set(MfaMode.SENSITIVE);

        mvc.perform(OidcLinks.startLink(enrolled.session())).andExpect(status().isOk());
    }

    @Test
    void aSessionThatMustChangeItsPasswordCannotLink() throws Exception {
        IdentityFixtures.User user = identity.newUser("initial password 1", Set.of(IdentityFixtures.MANAGER));
        Cookie session = identity.login(user.username(), "initial password 1");

        mvc.perform(OidcLinks.startLink(session)).andExpect(status().isForbidden());
    }

    @Test
    void withoutTheCsrfTokenNothingIsMarkedAndTheCallbackLinksNothing() throws Exception {
        IdentityFixtures.User user = identity.user(Set.of(IdentityFixtures.MANAGER), null);
        Cookie session = identity.login(user.username(), IdentityFixtures.STRONG_PASSWORD);
        String subject = OidcFlow.newSubject();

        mvc.perform(post(OidcRoutes.LINK).cookie(session)).andExpect(status().isForbidden());

        OidcFlow.Authorization authorization = flow.authorize(session);
        flow.callback(authorization, StubIdp.Grant.of(subject, authorization.nonce(), authorization.codeChallenge()));
        assertThat(linkedAccount(subject)).isNull();
    }

    @Test
    void aSupportAccountCannotLink() throws Exception {
        UUID support = IdentityFixtures.id(mvc.perform(post("/api/v1/backoffice/users").with(IdentityFixtures.admin())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(IdentityFixtures.json("username", IdentityFixtures.unique("sup"), "initialPassword",
                                "initial password 1", "roleIds", Set.of(), "support", true)))
                .andExpect(status().isCreated()).andReturn().getResponse());
        // as if the support account had changed its initial password under a grant (D83)
        jdbc.sql("UPDATE identity.user_account SET must_change_password = false WHERE id = ?").param(support)
                .update();

        mvc.perform(post(OidcRoutes.LINK).with(IdentityFixtures.asAccount(support))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SUPPORT_ACCOUNT_CANNOT_LINK"));
    }

    @Test
    void anAccountWithALinkGets409() throws Exception {
        IdentityFixtures.User user = identity.user(Set.of(IdentityFixtures.MANAGER), null);
        links.link(user, OidcFlow.newSubject());
        Cookie session = identity.login(user.username(), IdentityFixtures.STRONG_PASSWORD);

        mvc.perform(OidcLinks.startLink(session)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXTERNAL_IDENTITY_EXISTS"));
    }

    @Test
    void anIdentityLinkedToAnotherAccountIsNotLinkedAgain() throws Exception {
        String subject = OidcFlow.newSubject();
        IdentityFixtures.User first = identity.user(Set.of(IdentityFixtures.MANAGER), null);
        links.link(first, subject);
        IdentityFixtures.User second = identity.user(Set.of(IdentityFixtures.MANAGER), null);
        Cookie session = identity.login(second.username(), IdentityFixtures.STRONG_PASSWORD);

        MockHttpServletResponse callback = links.completeLink(session, subject);

        assertThat(callback.getRedirectedUrl()).isEqualTo("/backoffice/?error=oidc-link");
        assertThat(linkedAccount(subject)).isEqualTo(first.id());
        assertThat(jdbc.sql("SELECT count(*) FROM identity.external_identity WHERE account_id = ?")
                .param(second.id()).query(Long.class).single()).isZero();
    }

    @Test
    void aLinkRequestExpiresAfterTenMinutes() throws Exception {
        IdentityFixtures.User user = identity.user(Set.of(IdentityFixtures.MANAGER), null);
        Cookie session = identity.login(user.username(), IdentityFixtures.STRONG_PASSWORD);
        String subject = OidcFlow.newSubject();
        mvc.perform(OidcLinks.startLink(session)).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        OidcFlow.Authorization authorization = flow.authorize(session);
        flow.callback(authorization, StubIdp.Grant.of(subject, authorization.nonce(), authorization.codeChallenge()));

        assertThat(linkedAccount(subject)).isNull();
    }

    private UUID linkedAccount(String subject) {
        return jdbc.sql("SELECT account_id FROM identity.external_identity WHERE issuer = ? AND subject = ?")
                .param(OidcTest.IDP.issuer()).param(subject).query(UUID.class).optional().orElse(null);
    }
}
