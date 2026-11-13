package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.json;
import static com.xavier.backend.identity.MfaFixtures.asAdministrator;
import static com.xavier.backend.identity.MfaFixtures.confirm;
import static com.xavier.backend.identity.MfaFixtures.from;
import static com.xavier.backend.identity.MfaFixtures.loginWithCode;
import static com.xavier.backend.identity.MfaFixtures.stepUp;
import static com.xavier.backend.identity.MfaFixtures.verify;
import static com.xavier.backend.identity.MfaTest.LAN_ADDRESS;
import static com.xavier.backend.identity.MfaTest.REMOTE_ADDRESS;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.User;
import com.xavier.backend.identity.MfaFixtures;
import com.xavier.backend.identity.MfaTest;
import com.xavier.backend.identity.MutableClock;
import com.xavier.backend.identity.SwitchableMfaPolicy;
import com.xavier.backend.identity.mfa.Base32;
import com.xavier.backend.identity.mfa.MfaMode;
import jakarta.servlet.http.Cookie;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A58 (D140, D141, D145) with {@code lan-cidrs} holding {@code 192.168.1.0/24}: who must prove a second factor at
 * login in each mode, from the LAN and from outside, enrolled or not, and as a support account; a LAN session used
 * from outside. The full decision matrix is {@code MfaPolicyTest}; these are the HTTP flows.
 */
@MfaTest
class MfaRemotePolicyTest {

    static final String PASSWORD = "initial password 1";

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    SwitchableMfaPolicy policy;

    IdentityFixtures fixtures;
    MfaFixtures mfa;
    User administrator;
    MfaFixtures.Enrolled enrolled;
    User unenrolled;

    @BeforeEach
    void accounts() throws Exception {
        fixtures = new IdentityFixtures(mvc);
        mfa = new MfaFixtures(mvc, clock);
        administrator = fixtures.user(Set.of(IdentityFixtures.ADMINISTRATOR), null);
        enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.ADMINISTRATOR));
        unenrolled = fixtures.user(Set.of(IdentityFixtures.WAITER), null);
    }

    @AfterEach
    void reset() {
        policy.reset();
    }

    // --- SENSITIVE: a second factor when remote ---

    @Test
    void sensitiveAsksNothingOnTheLanAndASecondFactorFromOutside() throws Exception {
        policy.set(MfaMode.SENSITIVE);

        login(enrolled.user(), LAN_ADDRESS).andExpect(status().isOk()).andExpect(jsonPath("$.mfa.state").value("NONE"))
                .andExpect(jsonPath("$.permissions").isNotEmpty());
        login(unenrolled, LAN_ADDRESS).andExpect(status().isOk()).andExpect(jsonPath("$.mfa.state").value("NONE"));
        login(enrolled.user(), REMOTE_ADDRESS).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa.state").value("PENDING")).andExpect(jsonPath("$.permissions").isEmpty());
    }

    @Test
    void aRemoteAccountWithoutAFactorIsRefusedUnlessItHasAnEnrolmentCode() throws Exception {
        String code = mfa.resetBy(administrator.id(), unenrolled.id());
        policy.set(MfaMode.SENSITIVE);

        login(unenrolled, REMOTE_ADDRESS).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_NOT_ENROLLED"));
        mvc.perform(loginWithCode(unenrolled.username(), IdentityFixtures.STRONG_PASSWORD, "AAAA-BBBB-CCCC")
                        .with(from(REMOTE_ADDRESS)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MFA_ENROLMENT_CODE_INVALID"));

        Cookie session = mvc.perform(loginWithCode(unenrolled.username(), IdentityFixtures.STRONG_PASSWORD, code)
                        .with(from(REMOTE_ADDRESS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa.state").value("ENROLMENT_REQUIRED"))
                .andExpect(jsonPath("$.permissions").isEmpty())
                .andReturn().getResponse().getCookie("SESSION");
        mvc.perform(get("/api/v1/backoffice/users").cookie(session).with(from(REMOTE_ADDRESS)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(MustChangeCredentialFilter.MFA_ENROLMENT_DETAIL));

        mvc.perform(mfa.startEnrolment(session, null).with(from(REMOTE_ADDRESS))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLMENT_CODE_REQUIRED"));
        String secret = MfaFixtures.body(mvc.perform(mfa.startEnrolment(session, code).with(from(REMOTE_ADDRESS)))
                .andExpect(status().isOk())).get("secret").asString();
        mvc.perform(confirm(session, mfa.nextCode(Base32.decode(secret))).with(from(REMOTE_ADDRESS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.mfa.state").value("VERIFIED"))
                .andExpect(jsonPath("$.session.permissions").isNotEmpty())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10));
    }

    @Test
    void aLanSessionUsedFromOutsideNeedsASecondFactor() throws Exception {
        policy.set(MfaMode.SENSITIVE);
        Cookie lan = cookie(login(enrolled.user(), LAN_ADDRESS));
        MockHttpServletRequestBuilder stores = get("/api/v1/backoffice/stores").cookie(lan);

        mvc.perform(stores.with(from(LAN_ADDRESS))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/backoffice/stores").cookie(lan).with(from(REMOTE_ADDRESS)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MFA_REQUIRED"));

        mvc.perform(stepUp(lan, mfa.nextCode(enrolled.secret())).with(from(REMOTE_ADDRESS)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/backoffice/stores").cookie(lan).with(from(REMOTE_ADDRESS)))
                .andExpect(status().isOk());
    }

    @Test
    void aRemoteVerifiedLoginIsServedFromOutside() throws Exception {
        policy.set(MfaMode.SENSITIVE);
        Cookie pending = cookie(login(enrolled.user(), REMOTE_ADDRESS));
        Cookie verified = cookie(mvc.perform(verify(pending, mfa.nextCode(enrolled.secret()))
                .with(from(REMOTE_ADDRESS))).andExpect(status().isOk()));

        mvc.perform(get("/api/v1/backoffice/stores").cookie(verified).with(from(REMOTE_ADDRESS)))
                .andExpect(status().isOk());
    }

    // --- OFF and ALL_BACKOFFICE ---

    @Test
    void offAsksNothingAnywhere() throws Exception {
        login(enrolled.user(), REMOTE_ADDRESS).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa.state").value("NONE"));
        Cookie remote = cookie(login(unenrolled, REMOTE_ADDRESS).andExpect(jsonPath("$.mfa.state").value("NONE")));

        mvc.perform(get("/api/v1/auth/session").cookie(remote).with(from(REMOTE_ADDRESS))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/backoffice/users").cookie(cookie(login(enrolled.user(), REMOTE_ADDRESS)))
                .with(from(REMOTE_ADDRESS))).andExpect(status().isOk());
    }

    @Test
    void allBackofficeAsksEveryLoginAndLetsTheLanEnrolWithoutACode() throws Exception {
        policy.set(MfaMode.ALL_BACKOFFICE);

        login(enrolled.user(), LAN_ADDRESS).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa.state").value("PENDING"));
        login(unenrolled, REMOTE_ADDRESS).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_NOT_ENROLLED"));
        Cookie lan = cookie(login(unenrolled, LAN_ADDRESS).andExpect(jsonPath("$.mfa.state")
                .value("ENROLMENT_REQUIRED")));

        String secret = MfaFixtures.body(mvc.perform(mfa.startEnrolment(lan, null).with(from(LAN_ADDRESS)))
                .andExpect(status().isOk())).get("secret").asString();
        mvc.perform(confirm(lan, mfa.nextCode(Base32.decode(secret))).with(from(LAN_ADDRESS)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.session.mfa.state").value("VERIFIED"));
    }

    // --- support accounts (D145) ---

    @Test
    void aSupportAccountAlwaysNeedsASecondFactorAndEnrolsOnlyWithACode() throws Exception {
        User support = supportAccount();
        String code = mfa.resetBy(administrator.id(), support.id());
        policy.set(MfaMode.SENSITIVE);

        mvc.perform(IdentityFixtures.loginRequest(support.username(), PASSWORD).with(from(LAN_ADDRESS)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MFA_NOT_ENROLLED"));

        Cookie first = cookie(mvc.perform(loginWithCode(support.username(), PASSWORD, code).with(from(LAN_ADDRESS)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true)));
        Cookie changed = cookie(mvc.perform(post("/api/v1/auth/password").cookie(first).with(csrf())
                        .with(from(LAN_ADDRESS)).contentType(MediaType.APPLICATION_JSON)
                        .content(json("currentPassword", PASSWORD, "newPassword", IdentityFixtures.STRONG_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa.state").value("ENROLMENT_REQUIRED"))
                .andExpect(jsonPath("$.permissions").isEmpty()));

        mvc.perform(mfa.startEnrolment(changed, null).with(from(LAN_ADDRESS))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLMENT_CODE_REQUIRED"));
        byte[] secret = Base32.decode(MfaFixtures.body(mvc.perform(mfa.startEnrolment(changed, code)
                .with(from(LAN_ADDRESS))).andExpect(status().isOk())).get("secret").asString());
        mvc.perform(confirm(changed, mfa.nextCode(secret)).with(from(LAN_ADDRESS))).andExpect(status().isOk());

        login(support, LAN_ADDRESS).andExpect(status().isOk()).andExpect(jsonPath("$.mfa.state").value("PENDING"));
    }

    @Test
    void aPosSessionFromOutsideTheLanIsUntouched() throws Exception {
        UUID store = fixtures.store();
        IdentityFixtures.Terminal terminal = fixtures.enrolledTerminal(store);
        IdentityFixtures.Employee employee = fixtures.employee("4321", store, IdentityFixtures.WAITER);
        policy.set(MfaMode.SENSITIVE);

        Cookie pos = cookie(mvc.perform(IdentityFixtures.posLoginRequest(terminal.token(), employee.code(), "4321")
                .with(from(REMOTE_ADDRESS))).andExpect(status().isOk()));
        mvc.perform(get("/api/v1/auth/session").cookie(pos).with(from(REMOTE_ADDRESS))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/pos/catalog/daily-dishes").cookie(pos).with(from(REMOTE_ADDRESS)))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                        .isNotEqualTo(403));
    }

    private User supportAccount() throws Exception {
        String username = IdentityFixtures.unique("sup");
        UUID id = IdentityFixtures.id(mvc.perform(post("/api/v1/backoffice/users").with(asAdministrator(
                        administrator.id())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", username, "initialPassword", PASSWORD, "roleIds", Set.of(),
                                "support", true)))
                .andExpect(status().isCreated()).andReturn().getResponse());
        mvc.perform(post("/api/v1/backoffice/support-grants").with(asAdministrator(administrator.id())).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("accountId", id, "durationMinutes", 120, "reason", "vendor ticket")))
                .andExpect(status().isCreated());
        return new User(id, username);
    }

    private org.springframework.test.web.servlet.ResultActions login(User user, String address) throws Exception {
        return mvc.perform(IdentityFixtures.loginRequest(user.username(), IdentityFixtures.STRONG_PASSWORD)
                .with(from(address)));
    }

    private static Cookie cookie(org.springframework.test.web.servlet.ResultActions result) {
        return result.andReturn().getResponse().getCookie("SESSION");
    }
}
