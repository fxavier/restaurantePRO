package com.xavier.bff;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import jakarta.servlet.http.Cookie;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One Spring context for every BFF test: a stubbed Keycloak realm, a stubbed backend and a
 * private_key_jwt client key in a temporary file. Logins go through the real authorization-code flow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
abstract class BffTestSupport {

    static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";
    static final StubAuthorizationServer AUTHORIZATION_SERVER = new StubAuthorizationServer();
    static final StubBackend BACKEND = new StubBackend();
    static final RSAKey CLIENT_KEY = StubAuthorizationServer.generate("backoffice-bff-test");
    static final String CLIENT_KEY_FILE = writeClientKey();

    @Autowired
    protected MockMvc mvc;

    @LocalServerPort
    protected int port;

    protected final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.keycloak.issuer-uri", AUTHORIZATION_SERVER::issuer);
        registry.add("restaurantpro.bff.backend-url", BACKEND::baseUrl);
        registry.add("restaurantpro.bff.client-key-file", () -> CLIENT_KEY_FILE);
        // Above the stub's one-second refresh hold, far below its one-minute hang.
        registry.add("restaurantpro.bff.idp-read-timeout", () -> "PT3S");
    }

    @BeforeEach
    void resetStubs() {
        AUTHORIZATION_SERVER.reset();
        BACKEND.reset();
    }

    /** Logs a user in through the authorization-code flow and returns the BFF session. */
    protected MockHttpSession login() throws Exception {
        return login(new MockHttpSession());
    }

    /** Logs in on an existing browser session, as a login from another tab of the same browser does. */
    protected MockHttpSession login(MockHttpSession session) throws Exception {
        MvcResult toKeycloak = mvc.perform(get("/oauth2/authorization/keycloak").session(session))
                .andExpect(status().is3xxRedirection()).andReturn();
        completeLogin(session, toKeycloak);
        return session;
    }

    /** Plays Keycloak's part after the BFF redirected the browser to it; returns the callback's result. */
    protected MvcResult completeLogin(MockHttpSession session, MvcResult toKeycloak) throws Exception {
        Map<String, String> authorization = authorizationParameters(toKeycloak);
        AUTHORIZATION_SERVER.expectNonce(authorization.get("nonce"));
        return mvc.perform(get("/login/oauth2/code/keycloak")
                        .param("code", "stub-code")
                        .param("state", authorization.get("state"))
                        .session(session))
                .andExpect(status().is3xxRedirection()).andReturn();
    }

    /**
     * Logs in over real HTTP against the running server, for tests that need the container's own
     * (thread-safe) session; returns the session cookie as a {@code Cookie} header value.
     */
    protected String loginOverHttp() throws Exception {
        HttpResponse<Void> toKeycloak = http.send(HttpRequest.newBuilder(local("/oauth2/authorization/keycloak")).build(),
                HttpResponse.BodyHandlers.discarding());
        Map<String, String> authorization = RecordedRequest.parse(
                URI.create(toKeycloak.headers().firstValue("Location").orElseThrow()).getRawQuery());
        AUTHORIZATION_SERVER.expectNonce(authorization.get("nonce"));
        HttpResponse<Void> callback = http.send(HttpRequest.newBuilder(
                        local("/login/oauth2/code/keycloak?code=stub-code&state=" + authorization.get("state")))
                .header("Cookie", sessionCookie(toKeycloak)).build(), HttpResponse.BodyHandlers.discarding());
        if (callback.statusCode() != 302) {
            throw new AssertionError("login callback answered " + callback.statusCode());
        }
        return sessionCookie(callback);
    }

    protected URI local(String pathAndQuery) {
        return URI.create("http://localhost:" + port + pathAndQuery);
    }

    static String sessionCookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith("__Host-bff-session="))
                .map(cookie -> cookie.substring(0, cookie.indexOf(';')))
                .findFirst().orElseThrow(() -> new AssertionError("no session cookie"));
    }

    /** What the SPA does on a state-changing call: echo the CSRF cookie in the header (double submit). */
    static RequestPostProcessor xsrf() {
        String token = UUID.randomUUID().toString();
        return request -> {
            request.setCookies(new Cookie(CSRF_COOKIE, token));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }

    static Map<String, String> authorizationParameters(MvcResult toKeycloak) {
        URI location = URI.create(toKeycloak.getResponse().getRedirectedUrl());
        if (!location.toString().startsWith(AUTHORIZATION_SERVER.issuer())) {
            throw new AssertionError("not a redirect to the authorization server: " + location);
        }
        return RecordedRequest.parse(location.getRawQuery());
    }

    private static String writeClientKey() {
        try {
            Path file = Files.createTempFile("backoffice-bff-key", ".jwk");
            file.toFile().deleteOnExit();
            Files.writeString(file, CLIENT_KEY.toJSONString());
            return file.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
