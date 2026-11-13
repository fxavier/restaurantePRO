package com.xavier.backend.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.MfaTest;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A61 / §11: OIDC enabled and the IdP unreachable from the start. The backend starts (discovery is lazy), the OIDC
 * routes answer 503 {@code OIDC_UNAVAILABLE}, password login works (P56), and the IdP is retried once it is back.
 */
@MfaTest
@ContextConfiguration(initializers = OidcIdpDownTest.Down.class)
class OidcIdpDownTest {

    static final StubIdp IDP = StubIdp.stopped();

    @Autowired
    MockMvc mvc;

    @AfterAll
    static void stop() {
        IDP.stop();
    }

    @Test
    void theBackendStartsAuthorizeIs503AndPasswordLoginWorksThenTheIdpIsRetried() throws Exception {
        mvc.perform(get(OidcRoutes.AUTHORIZE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("OIDC_UNAVAILABLE"));
        mvc.perform(get(OidcRoutes.BASE)).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));

        IdentityFixtures identity = new IdentityFixtures(mvc);
        IdentityFixtures.User user = identity.user(Set.of(IdentityFixtures.MANAGER), null);
        mvc.perform(IdentityFixtures.loginRequest(user.username(), IdentityFixtures.STRONG_PASSWORD))
                .andExpect(status().isOk());

        IDP.restart();
        mvc.perform(get(OidcRoutes.AUTHORIZE)).andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(IDP.issuer() + "/authorize?")));
        assertThat(IDP.requests()).containsExactly("/realm/.well-known/openid-configuration");
    }

    static class Down implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "restaurantpro.security.oidc.enabled=true",
                    "restaurantpro.security.oidc.issuer-uri=" + IDP.issuer(),
                    "restaurantpro.security.oidc.client-id=" + StubIdp.CLIENT_ID
            ).applyTo(context);
        }
    }
}
