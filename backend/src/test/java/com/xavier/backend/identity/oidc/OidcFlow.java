package com.xavier.backend.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The browser's side of the authorization-code flow through the real filter chain: {@code /authorize} (302 to the
 * IdP), the user authenticating at the stub (the test registers what the IdP will say), and {@code /callback}.
 */
public final class OidcFlow {

    private final MockMvc mvc;
    private final StubIdp idp;

    public OidcFlow(MockMvc mvc, StubIdp idp) {
        this.mvc = mvc;
        this.idp = idp;
    }

    /** The redirect to the IdP, and the session that holds the authorization request. */
    public record Authorization(Map<String, String> params, Cookie session, String location) {

        public String state() {
            return params.get("state");
        }

        public String nonce() {
            return params.get("nonce");
        }

        public String codeChallenge() {
            return params.get("code_challenge");
        }
    }

    /** {@code GET /authorize} on {@code session} (or a new one); asserts the 302 to the stub's endpoint. */
    public Authorization authorize(Cookie session) throws Exception {
        MockHttpServletRequestBuilder request = get(OidcRoutes.AUTHORIZE);
        if (session != null) {
            request.cookie(session);
        }
        MockHttpServletResponse response = mvc.perform(request).andExpect(status().isFound()).andReturn()
                .getResponse();
        String location = response.getRedirectedUrl();
        assertThat(location).startsWith(idp.issuer() + "/authorize?");
        Map<String, String> params = UriComponentsBuilder.fromUriString(location).build(true).getQueryParams()
                .toSingleValueMap().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        e -> java.net.URLDecoder.decode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8)));
        Cookie newSession = response.getCookie("SESSION");
        return new Authorization(params, newSession != null ? newSession : session, location);
    }

    /** The IdP authenticated {@code grant}'s subject: the browser comes back with a fresh code. */
    public MockHttpServletResponse callback(Authorization authorization, StubIdp.Grant grant,
                                            RequestPostProcessor... extras) throws Exception {
        String code = "code-" + UUID.randomUUID();
        idp.issue(code, grant);
        return callback(authorization.session(), code, authorization.state(), extras);
    }

    public MockHttpServletResponse callback(Cookie session, String code, String state, RequestPostProcessor... extras)
            throws Exception {
        MockHttpServletRequestBuilder request = get(OidcRoutes.CALLBACK).param("code", code).param("state", state);
        if (session != null) {
            request.cookie(session);
        }
        for (RequestPostProcessor extra : extras) {
            request.with(extra);
        }
        return mvc.perform(request).andExpect(status().isFound()).andReturn().getResponse();
    }

    /** The full round trip for {@code subject} with the IdP's {@code amr} and {@code claims}. */
    public MockHttpServletResponse login(String subject, List<String> amr, Map<String, Object> claims,
                                         RequestPostProcessor... extras) throws Exception {
        Authorization authorization = authorize(null);
        return callback(authorization, StubIdp.Grant.of(subject, authorization.nonce(),
                authorization.codeChallenge()).withAmr(amr).withClaims(claims), extras);
    }

    public static String newSubject() {
        return "sub-" + UUID.randomUUID();
    }
}
