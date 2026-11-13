package com.xavier.backend.identity.oidc;

import static com.xavier.backend.identity.MfaFixtures.from;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.MfaFixtures;
import com.xavier.backend.identity.MfaTest;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D141/P55: a federated login is a first factor. It meets the same second-factor rule as a password login, and with
 * {@code trust-idp-mfa=false} (the default) the IdP's own MFA ({@code amr}) changes nothing.
 */
@OidcTest
class OidcMfaPolicyTest {

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

    @BeforeEach
    void setUp() {
        flow = new OidcFlow(mvc, OidcTest.IDP);
        links = new OidcLinks(mvc, flow);
        mfa = new MfaFixtures(mvc, clock);
    }

    @AfterEach
    void reset() {
        policy.reset();
    }

    @Test
    void allBackofficeAFederatedLoginLandsInMfaPendingUntilTheLocalCode() throws Exception {
        MfaFixtures.Enrolled enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.MANAGER));
        String subject = linked(enrolled.session());
        policy.set(MfaMode.ALL_BACKOFFICE);

        Cookie pending = flow.login(subject, List.of("pwd"), Map.of()).getCookie("SESSION");

        mvc.perform(get("/api/v1/auth/session").cookie(pending)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa.state").value("PENDING"))
                .andExpect(jsonPath("$.permissions").isEmpty());
        mvc.perform(get("/api/v1/backoffice/users").cookie(pending)).andExpect(status().isForbidden());
        mvc.perform(MfaFixtures.verify(pending, mfa.nextCode(enrolled.secret()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa.state").value("VERIFIED"))
                .andExpect(jsonPath("$.permissions").isNotEmpty());
    }

    @Test
    void theIdpsOwnMfaDoesNotCountUnlessTrusted() throws Exception {
        MfaFixtures.Enrolled enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.MANAGER));
        String subject = linked(enrolled.session());
        policy.set(MfaMode.ALL_BACKOFFICE);

        MockHttpServletResponse login = flow.login(subject, List.of("pwd", "mfa", "otp", "hwk"), Map.of());

        mvc.perform(get("/api/v1/auth/session").cookie(login.getCookie("SESSION")))
                .andExpect(jsonPath("$.mfa.state").value("PENDING"))
                .andExpect(jsonPath("$.permissions").isEmpty());
        assertThat(OidcAudit.details(jdbc, "OIDC_LOGIN", enrolled.user().id().toString()))
                .last().asString().contains("PENDING");
    }

    @Test
    void sensitiveAsksTheSecondFactorOfARemoteFederatedLoginOnly() throws Exception {
        MfaFixtures.Enrolled enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.MANAGER));
        String subject = linked(enrolled.session());
        policy.set(MfaMode.SENSITIVE);

        Cookie remote = flow.login(subject, List.of("pwd"), Map.of(), from(MfaTest.REMOTE_ADDRESS))
                .getCookie("SESSION");
        Cookie local = flow.login(subject, List.of("pwd"), Map.of(), from(MfaTest.LAN_ADDRESS)).getCookie("SESSION");

        mvc.perform(get("/api/v1/auth/session").cookie(remote)).andExpect(jsonPath("$.mfa.state").value("PENDING"));
        mvc.perform(get("/api/v1/auth/session").cookie(local)).andExpect(jsonPath("$.mfa.state").value("NONE"))
                .andExpect(jsonPath("$.permissions").isNotEmpty());
    }

    @Test
    void anUnenrolledAccountEnrolsFromTheLanAndIsRefusedRemotely() throws Exception {
        IdentityFixtures.User user = new IdentityFixtures(mvc).user(Set.of(IdentityFixtures.MANAGER), null);
        String subject = OidcFlow.newSubject();
        links.link(user, subject);
        policy.set(MfaMode.ALL_BACKOFFICE);

        Cookie lan = flow.login(subject, List.of("pwd"), Map.of(), from(MfaTest.LAN_ADDRESS)).getCookie("SESSION");
        MockHttpServletResponse remote = flow.login(subject, List.of("pwd"), Map.of(), from(MfaTest.REMOTE_ADDRESS));

        mvc.perform(get("/api/v1/auth/session").cookie(lan))
                .andExpect(jsonPath("$.mfa.state").value("ENROLMENT_REQUIRED"))
                .andExpect(jsonPath("$.permissions").isEmpty());
        assertThat(remote.getRedirectedUrl()).isEqualTo("/backoffice/login?error=oidc");
        assertThat(OidcAudit.details(jdbc, "OIDC_LOGIN_REFUSED", user.id().toString())).singleElement().asString()
                .contains("MFA_NOT_ENROLLED");
    }

    private String linked(Cookie session) throws Exception {
        String subject = OidcFlow.newSubject();
        assertThat(links.completeLink(session, subject).getRedirectedUrl()).isEqualTo("/backoffice/");
        return subject;
    }
}
