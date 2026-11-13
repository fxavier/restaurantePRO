package com.xavier.backend.identity;

import static com.xavier.backend.identity.IdentityFixtures.JSON;
import static com.xavier.backend.identity.IdentityFixtures.json;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.mfa.Base32;
import com.xavier.backend.identity.mfa.Totp;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * Second-factor steps through the real HTTP API. Codes come from the secret the enrolment returned and the test's
 * {@link MutableClock}; {@link #nextCode} moves the clock one TOTP step first, so a code is never one already used
 * (replay protection would refuse it).
 */
public final class MfaFixtures {

    public static final String MFA = "/api/v1/auth/mfa";

    private final MockMvc mvc;
    private final MutableClock clock;
    private final IdentityFixtures identity;

    public MfaFixtures(MockMvc mvc, MutableClock clock) {
        this.mvc = mvc;
        this.clock = clock;
        this.identity = new IdentityFixtures(mvc);
    }

    /** An account with an active factor, and the session that enrolled it (VERIFIED). */
    public record Enrolled(IdentityFixtures.User user, byte[] secret, List<String> recoveryCodes, Cookie session) {
    }

    /** A new account with {@code roles}, password changed, enrolled from the LAN. Call while the mode allows it. */
    public Enrolled enrolledUser(Set<UUID> roles) throws Exception {
        IdentityFixtures.User user = identity.user(roles, null);
        return enrol(user, identity.login(user.username(), IdentityFixtures.STRONG_PASSWORD));
    }

    /** Enrols {@code user} on {@code session} from the LAN; returns the new (VERIFIED) session. */
    public Enrolled enrol(IdentityFixtures.User user, Cookie session) throws Exception {
        JsonNode started = body(mvc.perform(startEnrolment(session, null)).andExpect(status().isOk()));
        byte[] secret = Base32.decode(started.get("secret").asString());
        var confirmed = mvc.perform(confirm(session, nextCode(secret))).andExpect(status().isOk()).andReturn()
                .getResponse();
        List<String> codes = new ArrayList<>();
        JSON.readTree(confirmed.getContentAsString()).get("recoveryCodes").forEach(c -> codes.add(c.asString()));
        return new Enrolled(user, secret, codes, confirmed.getCookie("SESSION"));
    }

    public MockHttpServletRequestBuilder startEnrolment(Cookie session, String enrolmentCode) throws Exception {
        return post(MFA + "/enrolment").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json("currentPassword", IdentityFixtures.STRONG_PASSWORD, "enrolmentCode", enrolmentCode));
    }

    public static MockHttpServletRequestBuilder confirm(Cookie session, String code) throws Exception {
        return post(MFA + "/enrolment/confirm").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json("code", code));
    }

    public static MockHttpServletRequestBuilder verify(Cookie session, String code) throws Exception {
        return post(MFA + "/verify").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json("code", code));
    }

    public static MockHttpServletRequestBuilder verifyRecovery(Cookie session, String recoveryCode) throws Exception {
        return post(MFA + "/verify").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json("recoveryCode", recoveryCode));
    }

    public static MockHttpServletRequestBuilder stepUp(Cookie session, String code) throws Exception {
        return post(MFA + "/step-up").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json("code", code));
    }

    /** Moves the clock one step on and returns the code of the new step. */
    public String nextCode(byte[] secret) {
        clock.advance(Duration.ofSeconds(Totp.PERIOD));
        return currentCode(secret);
    }

    public String currentCode(byte[] secret) {
        return "%06d".formatted(Totp.codeAt(secret, Totp.stepAt(clock.instant())));
    }

    /** A code that is not the current one (nor a neighbour). */
    public String wrongCode(byte[] secret) {
        long step = Totp.stepAt(clock.instant());
        for (int candidate = 0; ; candidate++) {
            boolean neighbour = false;
            for (long s = step - 1; s <= step + 1; s++) {
                neighbour |= Totp.codeAt(secret, s) == candidate;
            }
            if (!neighbour) {
                return "%06d".formatted(candidate);
            }
        }
    }

    /** Every permission installation-wide, as the real account {@code accountId} (FKs need a real account). */
    public static RequestPostProcessor asAdministrator(UUID accountId) {
        com.xavier.backend.access.Actor actor = com.xavier.backend.access.Actor.account(accountId,
                java.util.Arrays.stream(com.xavier.backend.access.Permission.values())
                        .map(com.xavier.backend.access.Permission::authority)
                        .collect(java.util.stream.Collectors.toSet()));
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                        .authenticated(actor, null, actor.authorities().stream()
                                .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                                .toList()));
    }

    public static MockHttpServletRequestBuilder reset(UUID accountId, String reason) throws Exception {
        return post("/api/v1/backoffice/users/" + accountId + "/mfa/reset").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json("reason", reason));
    }

    /** An administrator's reset of {@code accountId} (call while the mode is OFF); returns the enrolment code. */
    public String resetBy(UUID administratorId, UUID accountId) throws Exception {
        return body(mvc.perform(reset(accountId, "lost phone").with(asAdministrator(administratorId)))
                .andExpect(status().isOk())).get("enrolmentCode").asString();
    }

    public static MockHttpServletRequestBuilder loginWithCode(String username, String password, String code)
            throws Exception {
        return post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json("username", username, "password", password, "enrolmentCode", code));
    }

    public static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    public Cookie login(String username, String address) throws Exception {
        return mvc.perform(IdentityFixtures.loginRequest(username, IdentityFixtures.STRONG_PASSWORD).with(from(address)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
    }

    public static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
