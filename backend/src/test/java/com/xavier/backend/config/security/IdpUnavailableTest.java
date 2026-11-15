package com.xavier.backend.config.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A85, D176: the backend starts while Keycloak is down (the context of this test loads with both issuers on a port
 * where nothing listens: the key set is fetched lazily), and a token it cannot verify, because no key is cached and
 * the key set cannot be fetched, is 503 {@code IDP_UNAVAILABLE}, never accepted and never a 401 that would log the
 * user out.
 */
@SecurityHttpTest
@TestPropertySource(properties = {
        "restaurantpro.test.jwt=false",
        "restaurantpro.security.issuers.tenant=" + IdpUnavailableTest.DOWN + "/realms/restaurantpro",
        "restaurantpro.security.issuers.ops=" + IdpUnavailableTest.DOWN + "/realms/restaurantpro-ops"
})
class IdpUnavailableTest {

    /** Nothing listens on the discard port of the loopback interface. */
    static final String DOWN = "http://127.0.0.1:9";

    @Autowired
    MockMvc mvc;

    @Test
    void aTokenThatCannotBeVerifiedIs503IdpUnavailable() throws Exception {
        mvc.perform(get("/api/v1/auth/tenants").with(TestJwtFactory.account("down-user")
                        .claim("iss", DOWN + "/realms/restaurantpro").bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDP_UNAVAILABLE"));
    }

    @Test
    void aTokenOfTheOpsRealmIs503Too() throws Exception {
        mvc.perform(get("/api/v1/backoffice/stores").with(TestJwtFactory.support("down-support",
                        java.time.Instant.now()).claim("iss", DOWN + "/realms/restaurantpro-ops").bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDP_UNAVAILABLE"));
    }

    @Test
    void withoutATokenItIsStillAPlain401() throws Exception {
        mvc.perform(get("/api/v1/backoffice/stores")).andExpect(status().isUnauthorized());
    }
}
