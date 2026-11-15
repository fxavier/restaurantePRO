package com.xavier.bff;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BffLogoutTest extends BffTestSupport {

    @Test
    void logoutEndsTheBffSessionAndKeycloaksSession() throws Exception {
        MockHttpSession session = login();
        String idToken = AUTHORIZATION_SERVER.lastIdToken();

        MvcResult logout = mvc.perform(post("/bff/logout").session(session).with(xsrf()))
                .andExpect(status().is3xxRedirection()).andReturn();

        URI endSession = URI.create(logout.getResponse().getRedirectedUrl());
        assertThat(endSession.getPath()).isEqualTo(StubAuthorizationServer.REALM_PATH + "/protocol/openid-connect/logout");
        Map<String, String> parameters = RecordedRequest.parse(endSession.getRawQuery());
        assertThat(parameters).containsEntry("id_token_hint", idToken).containsKey("post_logout_redirect_uri");
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/backoffice/stores").session(new MockHttpSession())).andExpect(status().isUnauthorized());
        assertThat(BACKEND.received()).isEmpty();
    }
}
