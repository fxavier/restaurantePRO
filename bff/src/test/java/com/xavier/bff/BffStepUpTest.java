package com.xavier.bff;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BffStepUpTest extends BffTestSupport {

    static final String CHALLENGE = "Bearer error=\"insufficient_user_authentication\", "
            + "error_description=\"A different authentication level is required\", acr_values=\"2\", max_age=300";
    static final String PROBLEM = "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"code\":\"MFA_STEP_UP_REQUIRED\"}";

    @Test
    void theStepUpChallengeIsPassedThroughUnchangedAndNeverReplayed() throws Exception {
        MockHttpSession session = login();
        int tokenCallsAfterLogin = AUTHORIZATION_SERVER.tokenRequests().size();
        BACKEND.willRespond(401, Map.of("Content-Type", "application/problem+json", "WWW-Authenticate", CHALLENGE), PROBLEM);

        mvc.perform(post("/api/v1/backoffice/fiscal/series").session(session).with(xsrf())
                        .header("Idempotency-Key", "key-9").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", CHALLENGE))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(content().string(PROBLEM));

        assertThat(BACKEND.received()).hasSize(1);
        assertThat(AUTHORIZATION_SERVER.tokenRequests()).hasSize(tokenCallsAfterLogin);
    }

    @Test
    void theStepUpRedirectCarriesAcrValuesAndMaxAge() throws Exception {
        MockHttpSession session = login();

        Map<String, String> authorization = authorizationParameters(stepUp(session, "/fiscal/series?store=1"));

        assertThat(authorization).containsEntry("acr_values", "2").containsEntry("max_age", "300")
                .containsEntry("code_challenge_method", "S256").containsKey("code_challenge")
                .containsEntry("client_id", "backoffice-bff");
    }

    @Test
    void afterTheStepUpTheBrowserReturnsToTheRequestedPath() throws Exception {
        MockHttpSession session = login();

        MvcResult callback = completeLogin(session, stepUp(session, "/fiscal/series?store=1"));

        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/fiscal/series?store=1");
    }

    @Test
    void anAbandonedStepUpDoesNotRedirectALaterLogin() throws Exception {
        MockHttpSession session = login();
        stepUp(session, "/fiscal");

        MvcResult toKeycloak = mvc.perform(get("/oauth2/authorization/keycloak").session(session)).andReturn();
        MvcResult callback = completeLogin(session, toKeycloak);

        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void anOrdinaryLoginAsksForNoStepUp() throws Exception {
        MockHttpSession session = login();
        stepUp(session, "/fiscal");

        MvcResult toKeycloak = mvc.perform(get("/oauth2/authorization/keycloak").session(session)).andReturn();

        assertThat(authorizationParameters(toKeycloak)).doesNotContainKeys("acr_values", "max_age");
    }

    @Test
    void anOpenRedirectReturnIsRefused() throws Exception {
        MockHttpSession session = login();

        for (String target : new String[] {"https://evil.example/x", "//evil.example/x", "///evil.example/x", "/\t/evil.example", "/\\evil.example", "\\\\evil.example",
                "javascript:alert(1)", "fiscal", "/fiscal\r\nSet-Cookie: x=1", ""}) {
            mvc.perform(get("/bff/step-up").session(session)
                            .param("acr_values", "2").param("max_age", "300").param("return", target))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void unsupportedStepUpValuesAreRefused() throws Exception {
        MockHttpSession session = login();

        for (String[] values : new String[][] {{"9", "300"}, {"2", "-1"}, {"2", "abc"}, {"2\" foo", "300"}}) {
            mvc.perform(get("/bff/step-up").session(session)
                            .param("acr_values", values[0]).param("max_age", values[1]).param("return", "/fiscal"))
                    .andExpect(status().isBadRequest());
        }
    }

    private MvcResult stepUp(MockHttpSession session, String returnPath) throws Exception {
        mvc.perform(get("/bff/step-up").session(session)
                        .param("acr_values", "2").param("max_age", "300").param("return", returnPath))
                .andExpect(redirectedUrl("/oauth2/authorization/keycloak"));
        return mvc.perform(get("/oauth2/authorization/keycloak").session(session))
                .andExpect(status().is3xxRedirection()).andReturn();
    }
}
