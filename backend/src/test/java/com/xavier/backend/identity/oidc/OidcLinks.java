package com.xavier.backend.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Linking through the real API: the account's own session asks, the browser goes to the IdP and comes back. */
public final class OidcLinks {

    private final MockMvc mvc;
    private final OidcFlow flow;

    public OidcLinks(MockMvc mvc, OidcFlow flow) {
        this.mvc = mvc;
        this.flow = flow;
    }

    public static MockHttpServletRequestBuilder startLink(Cookie session) {
        return post(OidcRoutes.LINK).cookie(session).with(csrf());
    }

    /** Logs {@code user} in with its password (a mode that asks no second factor) and links {@code subject}. */
    public void link(IdentityFixtures.User user, String subject) throws Exception {
        Cookie session = new IdentityFixtures(mvc).login(user.username(), IdentityFixtures.STRONG_PASSWORD);
        assertThat(completeLink(session, subject).getRedirectedUrl()).isEqualTo("/backoffice/");
    }

    /** {@code POST /link} on {@code session}, then the IdP round trip; returns the callback's response. */
    public MockHttpServletResponse completeLink(Cookie session, String subject) throws Exception {
        mvc.perform(startLink(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUri").value(OidcRoutes.AUTHORIZE));
        OidcFlow.Authorization authorization = flow.authorize(session);
        return flow.callback(authorization,
                StubIdp.Grant.of(subject, authorization.nonce(), authorization.codeChallenge()));
    }
}
