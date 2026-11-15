package com.xavier.bff;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BffTokenRelayTest extends BffTestSupport {

    @Test
    void relaysABackOfficeCallWithTheSessionAccessTokenAsBearer() throws Exception {
        MockHttpSession session = login();
        BACKEND.willRespond(200, Map.of("Content-Type", "application/json"), "{\"stores\":[]}");

        mvc.perform(get("/api/v1/backoffice/stores").queryParam("page", "2").session(session))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"stores\":[]}"));

        RecordedRequest relayed = BACKEND.onlyRequest();
        assertThat(relayed.method()).isEqualTo("GET");
        assertThat(relayed.uri().toString()).isEqualTo("/api/v1/backoffice/stores?page=2");
        assertThat(relayed.header("Authorization")).isEqualTo("Bearer " + AUTHORIZATION_SERVER.lastAccessToken());
    }

    @Test
    void relaysAuthRoutesToo() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/v1/auth/tenants").session(session)).andExpect(status().isOk());

        assertThat(BACKEND.onlyRequest().uri().getPath()).isEqualTo("/api/v1/auth/tenants");
    }

    @Test
    void relaysTheBodyAndIdempotencyKeyOfAStateChangingCall() throws Exception {
        MockHttpSession session = login();
        BACKEND.willRespond(201, Map.of("Content-Type", "application/json", "Location", "/api/v1/backoffice/items/7"), "{\"id\":7}");

        mvc.perform(post("/api/v1/backoffice/items").session(session).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Bife\"}")
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/backoffice/items/7"));

        RecordedRequest relayed = BACKEND.onlyRequest();
        assertThat(relayed.method()).isEqualTo("POST");
        assertThat(relayed.body()).isEqualTo("{\"name\":\"Bife\"}");
        assertThat(relayed.header("Content-Type")).startsWith("application/json");
        assertThat(relayed.header("Idempotency-Key")).isEqualTo("key-1");
    }

    @Test
    void stripsBrowserCookiesAndCredentialsTowardsTheBackend() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/v1/backoffice/stores").session(session)
                        .header("Cookie", "tracking=abc; XSRF-TOKEN=x")
                        .header("Authorization", "Bearer forged-by-the-browser")
                        .header("X-Forwarded-For", "203.0.113.9"))
                .andExpect(status().isOk());

        RecordedRequest relayed = BACKEND.onlyRequest();
        assertThat(relayed.headers()).doesNotContainKeys("cookie", "x-forwarded-for", "x-xsrf-token");
        assertThat(relayed.header("Authorization")).isEqualTo("Bearer " + AUTHORIZATION_SERVER.lastAccessToken());
    }

    @Test
    void neverExposesATokenToTheBrowser() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult toKeycloak = mvc.perform(get("/oauth2/authorization/keycloak").session(session)).andReturn();
        MvcResult callback = completeLogin(session, toKeycloak);
        BACKEND.willRespond(200, Map.of("Content-Type", "application/json", "Set-Cookie", "backend=1; Path=/"), "{\"ok\":true}");
        MvcResult relay = mvc.perform(get("/api/v1/backoffice/stores").session(session)).andReturn();

        for (MvcResult result : new MvcResult[] {toKeycloak, callback, relay}) {
            assertNoTokenIn(result.getResponse());
        }
        assertThat(relay.getResponse().getHeaders("Set-Cookie")).noneMatch(cookie -> cookie.startsWith("backend="));
    }

    @Test
    void exchangesTheCodeWithPkceAndPrivateKeyJwt() throws Exception {
        login();

        Map<String, String> exchange = AUTHORIZATION_SERVER.tokenRequests().getFirst().form();
        assertThat(exchange).containsEntry("grant_type", "authorization_code")
                .containsKey("code_verifier")
                .containsEntry("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
                .doesNotContainKey("client_secret");
        SignedJWT assertion = SignedJWT.parse(exchange.get("client_assertion"));
        assertThat(assertion.verify(new RSASSAVerifier(CLIENT_KEY.toRSAPublicKey()))).isTrue();
        assertThat(assertion.getJWTClaimsSet().getSubject()).isEqualTo("backoffice-bff");
    }

    @Test
    void refreshesAnExpiringAccessTokenAndKeepsTheRotatedRefreshToken() throws Exception {
        AUTHORIZATION_SERVER.issueAccessTokensValidFor(5);
        MockHttpSession session = login();
        String firstRefreshToken = AUTHORIZATION_SERVER.lastRefreshToken();

        mvc.perform(get("/api/v1/backoffice/stores").session(session)).andExpect(status().isOk());
        String rotatedRefreshToken = AUTHORIZATION_SERVER.lastRefreshToken();
        mvc.perform(get("/api/v1/backoffice/stores").session(session)).andExpect(status().isOk());

        var refreshes = AUTHORIZATION_SERVER.tokenRequests().stream()
                .map(RecordedRequest::form).filter(f -> "refresh_token".equals(f.get("grant_type"))).toList();
        assertThat(refreshes).hasSize(2);
        assertThat(refreshes.get(0).get("refresh_token")).isEqualTo(firstRefreshToken);
        assertThat(refreshes.get(1).get("refresh_token")).isEqualTo(rotatedRefreshToken);
        assertThat(refreshes).allSatisfy(f -> assertThat(f).containsKey("client_assertion"));
        assertThat(BACKEND.received().getLast().header("Authorization")).isEqualTo("Bearer " + AUTHORIZATION_SERVER.lastAccessToken());
    }

    @Test
    void aRefusedRefreshAnswers401WithoutCallingTheBackend() throws Exception {
        AUTHORIZATION_SERVER.issueAccessTokensValidFor(5);
        MockHttpSession session = login();
        AUTHORIZATION_SERVER.refuseRefresh();

        mvc.perform(get("/api/v1/backoffice/stores").session(session)).andExpect(status().isUnauthorized());

        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void anAnonymousCallAnswers401WithoutCallingTheBackend() throws Exception {
        mvc.perform(get("/api/v1/backoffice/stores")).andExpect(status().isUnauthorized());

        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void aPathThatEscapesTheRelayedPrefixNeverReachesTheBackend() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get(URI.create("/api/v1/backoffice/%2e%2e/%2e%2e/platform/tenants")).session(session))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/platform/tenants").session(session))
                .andExpect(status().isForbidden());

        assertThat(BACKEND.received()).isEmpty();
    }

    private static void assertNoTokenIn(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString();
        String headers = response.getHeaderNames().stream()
                .map(name -> name + ": " + String.join(",", response.getHeaders(name))).reduce("", String::concat);
        for (String token : new String[] {AUTHORIZATION_SERVER.lastAccessToken(), AUTHORIZATION_SERVER.lastRefreshToken(), AUTHORIZATION_SERVER.lastIdToken()}) {
            assertThat(body).doesNotContain(token);
            assertThat(headers).doesNotContain(token);
        }
    }
}
