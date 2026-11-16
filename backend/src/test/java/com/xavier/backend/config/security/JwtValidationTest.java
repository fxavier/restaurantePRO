package com.xavier.backend.config.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A85, D176, security rule 8: the backend accepts only a complete, valid access token, and refuses everything else
 * with 401 and the standard Bearer challenge ({@code error="invalid_token"}). The probe route needs a valid token and
 * no membership ({@code GET /api/v1/auth/tenants}), so each refusal below is the token's alone.
 */
@SecurityHttpTest
class JwtValidationTest {

    private static final String PROBE = "/api/v1/auth/tenants";

    @Autowired
    MockMvc mvc;

    private static TestJwtFactory token() {
        return TestJwtFactory.account("jwt-" + UUID.randomUUID());
    }

    private ResultActions call(String token) throws Exception {
        return mvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void refused(UnaryOperator<TestJwtFactory> change) throws Exception {
        refused(change.apply(token()).encode());
    }

    private void refused(String token) throws Exception {
        call(token).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
    }

    @Test
    void aValidRs256TokenIsAccepted() throws Exception {
        call(token().encode()).andExpect(status().isOk());
    }

    @Test
    void aValidEs256TokenIsAccepted() throws Exception {
        call(token().es256().encode()).andExpect(status().isOk());
    }

    /** An access token lives five minutes (D175): one issued four minutes ago is still valid. */
    @Test
    void aTokenIssuedFourMinutesAgoIsAccepted() throws Exception {
        call(token().claim("iat", Instant.now().minusSeconds(240)).claim("nbf", Instant.now().minusSeconds(240))
                .encode()).andExpect(status().isOk());
    }

    /** Keycloak's access tokens carry no {@code nbf}: it is checked when present, never required. */
    @Test
    void aTokenWithoutNotBeforeIsAccepted() throws Exception {
        call(token().without("nbf").encode()).andExpect(status().isOk());
    }

    @Test
    void noTokenIsAChallengeWithoutError() throws Exception {
        mvc.perform(get(PROBE)).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")));
    }

    @Test
    void algNoneIsRefused() throws Exception {
        refused(TestJwtFactory::unsigned);
    }

    @Test
    void hs256SignedWithThePublicKeyIsRefused() throws Exception {
        refused(TestJwtFactory::hmacWithPublicKey);
    }

    @Test
    void aSignatureOfAnUntrustedKeyIsRefused() throws Exception {
        refused(token().encodeWithForeignKey());
    }

    @Test
    void anotherIssuerIsRefused() throws Exception {
        refused(t -> t.claim("iss", "https://evil.example/realms/restaurantpro"));
    }

    @Test
    void anotherAudienceIsRefused() throws Exception {
        refused(t -> t.claim("aud", List.of("account")));
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        refused(t -> t.claim("exp", Instant.now().minusSeconds(60)));
    }

    @Test
    void aTokenNotYetValidIsRefused() throws Exception {
        refused(t -> t.claim("nbf", Instant.now().plusSeconds(120)));
    }

    @Test
    void aTokenIssuedInTheFutureIsRefused() throws Exception {
        refused(t -> t.claim("iat", Instant.now().plusSeconds(120)));
    }

    @Test
    void anIdTokenIsRefused() throws Exception {
        refused(t -> t.claim("typ", "ID"));
    }

    @Test
    void aRefreshTokenIsRefused() throws Exception {
        refused(t -> t.claim("typ", "Refresh"));
    }

    @Test
    void aTokenWithoutTypIsRefused() throws Exception {
        refused(t -> t.without("typ"));
    }

    @Test
    void anAuthorizedPartyOfAnotherRouteFamilyIsRefused() throws Exception {
        refused(t -> t.claim("azp", "pos-0190b000-0000-7000-8000-000000000001"));
    }

    @Test
    void aTokenWithoutAuthorizedPartyIsRefused() throws Exception {
        refused(t -> t.without("azp"));
    }

    @Test
    void aTokenWithoutExpiryIsRefused() throws Exception {
        refused(t -> t.without("exp"));
    }
}
