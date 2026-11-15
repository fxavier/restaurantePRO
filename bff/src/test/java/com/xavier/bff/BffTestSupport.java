package com.xavier.bff;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.keycloak.issuer-uri", AUTHORIZATION_SERVER::issuer);
        registry.add("restaurantpro.bff.backend-url", BACKEND::baseUrl);
        registry.add("restaurantpro.bff.client-key-file", () -> CLIENT_KEY_FILE);
    }

    @BeforeEach
    void resetStubs() {
        AUTHORIZATION_SERVER.reset();
        BACKEND.reset();
    }

    /** Logs a user in through the authorization-code flow and returns the BFF session. */
    protected MockHttpSession login() throws Exception {
        MockHttpSession session = new MockHttpSession();
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
