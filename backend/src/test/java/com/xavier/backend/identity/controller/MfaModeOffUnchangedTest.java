package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.MfaFixtures.from;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.AssuranceState;
import com.xavier.backend.access.AuthenticationAssurance;
import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityTest;
import com.xavier.backend.identity.mfa.MfaMode;
import com.xavier.backend.identity.service.MfaPolicy;
import jakarta.servlet.http.Cookie;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * P52 / A59: with the code default (no MFA property at all) an installation behaves exactly as R4: logins ask for no
 * second factor anywhere, sensitive permissions need no step-up, and a POS session keeps its BackOffice grants. The
 * plain identity test context, so the default comes from the real property binding, not a test switch.
 */
@IdentityTest
class MfaModeOffUnchangedTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MfaPolicy policy;

    @Autowired
    AuthenticationAssurance assurance;

    @Test
    void theDefaultModeIsOffAndTheAssuranceIsNotEnforced() {
        assertThat(policy.mode()).isEqualTo(MfaMode.OFF);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                com.xavier.backend.access.Actor.account(UUID.randomUUID(), Set.of()), null, java.util.List.of()));
        try {
            assertThat(assurance.current()).isSameAs(AssuranceState.NOT_ENFORCED);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void anR4LoginAndASensitiveCallAreUnchangedFromAnywhere() throws Exception {
        IdentityFixtures.User admin = new IdentityFixtures(mvc).user(Set.of(IdentityFixtures.ADMINISTRATOR), null);

        Cookie session = mvc.perform(IdentityFixtures.loginRequest(admin.username(), IdentityFixtures.STRONG_PASSWORD)
                        .with(from("203.0.113.5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa.state").value("NONE"))
                .andExpect(jsonPath("$.permissions").isNotEmpty())
                .andReturn().getResponse().getCookie("SESSION");

        mvc.perform(get("/api/v1/backoffice/users").cookie(session).with(from("203.0.113.5")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/backoffice/users").with(IdentityFixtures.admin())).andExpect(status().isOk());
    }

    @Test
    void aPosSessionKeepsItsSensitiveBackOfficeGrant() throws Exception {
        IdentityFixtures fixtures = new IdentityFixtures(mvc);
        UUID store = fixtures.store();
        UUID role = IdentityFixtures.id(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/backoffice/roles").with(IdentityFixtures.admin())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(IdentityFixtures.json("name", IdentityFixtures.unique("t"), "permissions",
                                Set.of(Permission.TERMINAL_MANAGE, Permission.DINING_VIEW))))
                .andExpect(status().isCreated()).andReturn().getResponse());
        IdentityFixtures.Employee employee = fixtures.employee("8642", store, role);
        Cookie pos = fixtures.posLogin(fixtures.enrolledTerminal(store), employee, "8642");

        mvc.perform(get("/api/v1/backoffice/terminals").param("storeId", store.toString()).cookie(pos))
                .andExpect(status().isOk());
    }
}
