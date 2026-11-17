package com.xavier.bff;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BffCsrfTest extends BffTestSupport {

    @Test
    void aStateChangingRelayWithoutTheCsrfTokenIsRefused() throws Exception {
        MockHttpSession session = login();

        mvc.perform(post("/api/v1/backoffice/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void aStateChangingRelayWithAForgedCsrfTokenIsRefused() throws Exception {
        MockHttpSession session = login();

        mvc.perform(post("/api/v1/backoffice/items").session(session)
                        .cookie(new Cookie(CSRF_COOKIE, "cookie-token")).header("X-XSRF-TOKEN", "forged-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void theSpaEchoesTheCsrfCookieInTheHeaderAndTheCallIsRelayed() throws Exception {
        MockHttpSession session = login();
        MvcResult read = mvc.perform(get("/api/v1/backoffice/stores").session(session)).andExpect(status().isOk()).andReturn();
        Cookie xsrf = read.getResponse().getCookie(CSRF_COOKIE);
        assertThat(xsrf).as("CSRF cookie readable by the SPA").isNotNull();
        assertThat(xsrf.isHttpOnly()).isFalse();
        assertThat(xsrf.getSecure()).isTrue();
        assertThat(xsrf.getPath()).isEqualTo("/");
        assertThat(xsrf.getAttribute("SameSite")).isEqualTo("Lax");

        mvc.perform(post("/api/v1/backoffice/items").session(session).cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(BACKEND.received().getLast().headers()).doesNotContainKeys("x-xsrf-token", "cookie");
    }

    @Test
    void tenantSelectionAndLogoutNeedTheCsrfTokenToo() throws Exception {
        MockHttpSession session = login();

        mvc.perform(put("/bff/tenant").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"00000000-0000-7000-8000-000000000001\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/bff/logout").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void theSessionIdChangesAtLoginAndThePreLoginCookieIsDead() throws Exception {
        HttpResponse<Void> toKeycloak = http.send(HttpRequest.newBuilder(local("/oauth2/authorization/keycloak")).build(),
                HttpResponse.BodyHandlers.discarding());
        String preLogin = sessionCookie(toKeycloak);
        Map<String, String> authorization = RecordedRequest.parse(
                URI.create(toKeycloak.headers().firstValue("Location").orElseThrow()).getRawQuery());
        AUTHORIZATION_SERVER.expectNonce(authorization.get("nonce"));

        HttpResponse<Void> callback = http.send(HttpRequest.newBuilder(
                        local("/login/oauth2/code/keycloak?code=stub-code&state=" + authorization.get("state")))
                .header("Cookie", preLogin).build(), HttpResponse.BodyHandlers.discarding());

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(sessionCookie(callback)).isNotEqualTo(preLogin);
        HttpResponse<Void> withPreLoginCookie = http.send(HttpRequest.newBuilder(local("/api/v1/backoffice/stores"))
                .header("Cookie", preLogin).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(withPreLoginCookie.statusCode()).isEqualTo(401);
        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void theSessionCookieIsHttpOnlySecureAndSameSiteLax() throws Exception {
        HttpResponse<Void> response = http.send(HttpRequest.newBuilder(local("/oauth2/authorization/keycloak")).build(),
                HttpResponse.BodyHandlers.discarding());

        String sessionCookie = response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("__Host-bff-session=")).findFirst().orElseThrow();
        assertThat(sessionCookie).containsIgnoringCase("HttpOnly").containsIgnoringCase("Secure")
                .containsIgnoringCase("SameSite=Lax").contains("Path=/");
    }
}
