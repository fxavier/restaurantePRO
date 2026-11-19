package com.xavier.bff;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    /**
     * FE-F10: the SPA logs out with fetch, which cannot follow a cross-origin redirect to Keycloak; it gets the
     * end-session URL instead and navigates there itself, so Keycloak's own cookies are cleared too.
     */
    @Test
    void aJsonLogoutAnswersTheEndSessionUrlInsteadOfARedirect() throws Exception {
        MockHttpSession session = login();

        MvcResult logout = mvc.perform(post("/bff/logout").session(session).with(xsrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        String body = logout.getResponse().getContentAsString();
        URI endSession = URI.create(JsonPath.read(body, "$.logoutUrl"));
        assertThat(endSession.toString()).startsWith(AUTHORIZATION_SERVER.issuer());
        assertThat(endSession.getPath()).isEqualTo(StubAuthorizationServer.LOGOUT_PATH);
        assertThat(RecordedRequest.parse(endSession.getRawQuery()))
                .containsOnlyKeys("client_id", "post_logout_redirect_uri")
                .containsEntry("client_id", "backoffice-bff")
                .containsEntry("post_logout_redirect_uri", "http://localhost/");
        assertThat(body).doesNotContain(AUTHORIZATION_SERVER.lastIdToken(), AUTHORIZATION_SERVER.lastAccessToken(),
                AUTHORIZATION_SERVER.lastRefreshToken());
        assertThat(logout.getResponse().getHeader("Location")).isNull();
        assertThat(session.isInvalid()).isTrue();
        assertThat(AUTHORIZATION_SERVER.received().stream()
                .filter(r -> r.uri().getPath().equals(StubAuthorizationServer.LOGOUT_PATH))).hasSize(1);
    }

    /** A browser form accepts anything, JSON included through a wildcard; it keeps the redirect. */
    @Test
    void aLogoutAcceptingAnyTypeKeepsTheRedirect() throws Exception {
        MockHttpSession session = login();

        mvc.perform(post("/bff/logout").session(session).with(xsrf())
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void aJsonLogoutEndsTheBffSessionEvenWhenKeycloakDoesNotAnswer() throws Exception {
        MockHttpSession session = login();
        AUTHORIZATION_SERVER.hangTokenEndpoint();

        mvc.perform(post("/bff/logout").session(session).with(xsrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void logoutEndsTheBffSessionEvenWhenKeycloakDoesNotAnswer() throws Exception {
        MockHttpSession session = login();
        AUTHORIZATION_SERVER.hangTokenEndpoint();

        mvc.perform(post("/bff/logout").session(session).with(xsrf())).andExpect(status().is3xxRedirection());

        assertThat(session.isInvalid()).isTrue();
    }
}
