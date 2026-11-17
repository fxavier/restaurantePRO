package com.xavier.backend.config.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spec "frozen contracts", properties: with the issuers unset (the default of the test configuration) every API call
 * is 503 {@code IDP_UNAVAILABLE}, with or without a token: the backend fails closed instead of trusting nothing.
 */
@SecurityHttpTest
@TestPropertySource(properties = {"restaurantpro.test.jwt=false", "restaurantpro.security.issuers.tenant=",
        "restaurantpro.security.issuers.ops="})
class IssuersUnsetTest {

    @Autowired
    MockMvc mvc;

    @Test
    void aTokenIs503() throws Exception {
        mvc.perform(get("/api/v1/auth/tenants").with(TestJwtFactory.account("unset-user").bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDP_UNAVAILABLE"));
    }

    @Test
    void noTokenIs503() throws Exception {
        mvc.perform(get("/api/v1/backoffice/stores"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDP_UNAVAILABLE"));
    }
}
