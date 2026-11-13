package com.xavier.backend.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A61, D144 "off by default": with {@code enabled=false} (and an issuer configured, but no client id) the whole
 * backend starts without calling the provider, the OIDC routes answer 404 and only the status says "disabled".
 */
@IdentityTest
@ContextConfiguration(initializers = OidcDisabledTest.Disabled.class)
class OidcDisabledTest {

    static final StubIdp IDP = StubIdp.start();

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Test
    void theBackendStartedWithoutAnyCallToTheProvider() {
        assertThat(IDP.requests()).isEmpty();
        assertThat(context.getBeanNamesForType(ClientRegistrationRepository.class)).isEmpty();
    }

    @Test
    void theOidcRoutesAnswer404() throws Exception {
        mvc.perform(get(OidcRoutes.AUTHORIZE)).andExpect(status().isNotFound());
        mvc.perform(get(OidcRoutes.CALLBACK).param("code", "c").param("state", "s"))
                .andExpect(status().isNotFound());
        mvc.perform(post(OidcRoutes.LINK).with(csrf()).with(IdentityFixtures.admin()))
                .andExpect(status().isNotFound());
        mvc.perform(get(OidcRoutes.LINK).with(IdentityFixtures.admin())).andExpect(status().isNotFound());
        mvc.perform(delete(OidcRoutes.LINK).with(csrf()).with(IdentityFixtures.admin()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/backoffice/users/" + UUID.randomUUID() + "/external-identity")
                .with(IdentityFixtures.admin())).andExpect(status().isNotFound());
        assertThat(IDP.requests()).isEmpty();
    }

    @Test
    void theStatusSaysDisabled() throws Exception {
        mvc.perform(get(OidcRoutes.BASE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.displayName").doesNotExist());
    }

    static class Disabled implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "restaurantpro.security.oidc.enabled=false",
                    "restaurantpro.security.oidc.issuer-uri=" + IDP.issuer()
            ).applyTo(context);
        }
    }
}
