package com.xavier.backend.identity.oidc;

import static com.xavier.backend.identity.MfaFixtures.from;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * P55's alternative, when the operator opts in ({@code trust-idp-mfa=true}): an ID token whose {@code amr} shows MFA
 * ({@code mfa}, {@code otp}, {@code hwk}, {@code swk}) makes the federated session VERIFIED; any other {@code amr}
 * still needs the local factor.
 */
@OidcTest
@TestPropertySource(properties = "restaurantpro.security.oidc.trust-idp-mfa=true")
class OidcTrustedIdpMfaTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    SwitchableMfaPolicy policy;

    OidcFlow flow;
    String subject;

    @BeforeEach
    void linkedEnrolledManager() throws Exception {
        flow = new OidcFlow(mvc, OidcTest.IDP);
        MfaFixtures.Enrolled enrolled = new MfaFixtures(mvc, clock).enrolledUser(Set.of(IdentityFixtures.MANAGER));
        subject = OidcFlow.newSubject();
        new OidcLinks(mvc, flow).completeLink(enrolled.session(), subject);
        policy.set(MfaMode.ALL_BACKOFFICE);
    }

    @AfterEach
    void reset() {
        policy.reset();
    }

    @Test
    void anAmrShowingMfaMakesTheSessionVerified() throws Exception {
        for (String method : List.of("mfa", "otp", "hwk", "swk")) {
            Cookie session = flow.login(subject, List.of("pwd", method), Map.of(), from(MfaTest.REMOTE_ADDRESS))
                    .getCookie("SESSION");

            mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.mfa.state").value("VERIFIED"))
                    .andExpect(jsonPath("$.mfa.stepUpValidUntil").isNotEmpty())
                    .andExpect(jsonPath("$.permissions").isNotEmpty());
        }
    }

    @Test
    void anAmrWithoutMfaStillNeedsTheLocalFactor() throws Exception {
        Cookie session = flow.login(subject, List.of("pwd", "kba"), Map.of()).getCookie("SESSION");

        mvc.perform(get("/api/v1/auth/session").cookie(session))
                .andExpect(jsonPath("$.mfa.state").value("PENDING"))
                .andExpect(jsonPath("$.permissions").isEmpty());
    }

    @Test
    void withMfaOffTheAmrChangesNothing() throws Exception {
        policy.set(MfaMode.OFF);

        Cookie session = flow.login(subject, List.of("mfa"), Map.of()).getCookie("SESSION");

        mvc.perform(get("/api/v1/auth/session").cookie(session))
                .andExpect(jsonPath("$.mfa.state").value("NONE"))
                .andExpect(jsonPath("$.permissions").isNotEmpty());
    }
}
