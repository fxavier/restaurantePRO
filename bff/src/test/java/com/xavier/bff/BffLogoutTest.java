package com.xavier.bff;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BffLogoutTest extends BffTestSupport {

    @Test
    void logoutEndsKeycloaksSessionOverTheBackChannel() throws Exception {
        MockHttpSession session = login();
        String refreshToken = AUTHORIZATION_SERVER.lastRefreshToken();

        mvc.perform(post("/bff/logout").session(session).with(xsrf())).andExpect(status().is3xxRedirection());

        List<RecordedRequest> backChannel = AUTHORIZATION_SERVER.received().stream()
                .filter(r -> r.uri().getPath().equals(StubAuthorizationServer.LOGOUT_PATH)).toList();
        assertThat(backChannel).hasSize(1);
        Map<String, String> form = backChannel.getFirst().form();
        assertThat(backChannel.getFirst().method()).isEqualTo("POST");
        assertThat(form).containsEntry("refresh_token", refreshToken)
                .containsEntry("client_id", "backoffice-bff")
                .containsEntry("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        assertThat(SignedJWT.parse(form.get("client_assertion")).verify(new RSASSAVerifier(CLIENT_KEY.toRSAPublicKey()))).isTrue();
    }

    @Test
    void logoutEndsTheBffSessionAndSendsTheBrowserToKeycloakWithoutAnyToken() throws Exception {
        MockHttpSession session = login();

        MvcResult logout = mvc.perform(post("/bff/logout").session(session).with(xsrf()))
                .andExpect(status().is3xxRedirection()).andReturn();

        String location = logout.getResponse().getRedirectedUrl();
        URI endSession = URI.create(location);
        assertThat(endSession.getPath()).isEqualTo(StubAuthorizationServer.LOGOUT_PATH);
        assertThat(RecordedRequest.parse(endSession.getRawQuery()))
                .containsOnlyKeys("client_id", "post_logout_redirect_uri")
                .containsEntry("client_id", "backoffice-bff")
                .containsEntry("post_logout_redirect_uri", "http://localhost/");
        assertThat(location).doesNotContain(AUTHORIZATION_SERVER.lastIdToken(), AUTHORIZATION_SERVER.lastAccessToken(),
                AUTHORIZATION_SERVER.lastRefreshToken());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/backoffice/stores").session(new MockHttpSession())).andExpect(status().isUnauthorized());
        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void logoutEndsTheBffSessionEvenWhenKeycloakDoesNotAnswer() throws Exception {
        MockHttpSession session = login();
        AUTHORIZATION_SERVER.hangTokenEndpoint();

        mvc.perform(post("/bff/logout").session(session).with(xsrf())).andExpect(status().is3xxRedirection());

        assertThat(session.isInvalid()).isTrue();
    }
}
