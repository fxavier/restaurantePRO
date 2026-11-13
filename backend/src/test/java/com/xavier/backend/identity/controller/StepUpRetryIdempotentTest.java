package com.xavier.backend.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.Publications;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.MfaFixtures;
import com.xavier.backend.identity.MfaTest;
import com.xavier.backend.identity.MutableClock;
import com.xavier.backend.identity.SwitchableMfaPolicy;
import com.xavier.backend.identity.mfa.MfaMode;
import jakarta.servlet.http.Cookie;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A59 (D142, D47): publishing a release without a fresh second factor is refused and nothing is stored under its
 * Idempotency-Key (no completed response: only 2xx is stored); after the step-up the same request with the same key publishes once, and repeating it replays.
 */
@MfaTest
class StepUpRetryIdempotentTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    SwitchableMfaPolicy policy;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void reset() {
        policy.reset();
    }

    @Test
    void theRefusedPublicationIsRetriedWithTheSameKeyAndPublishesOnce() throws Exception {
        Publications publications = new Publications(mvc, jdbc);
        UUID store = publications.sellableStore().store();
        UUID release = publications.validatedRelease(store);
        MfaFixtures mfa = new MfaFixtures(mvc, clock);
        UUID role = IdentityFixtures.id(mvc.perform(post("/api/v1/backoffice/roles").with(IdentityFixtures.admin())
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(IdentityFixtures.json("name", IdentityFixtures.unique("pub"), "permissions",
                                Set.of(Permission.CATALOG_PUBLISH))))
                .andExpect(status().isCreated()).andReturn().getResponse());
        MfaFixtures.Enrolled publisher = mfa.enrolledUser(Set.of(role));
        Cookie session = mfa.login(publisher.user().username(), MfaTest.LAN_ADDRESS);
        String key = UUID.randomUUID().toString();
        policy.set(MfaMode.SENSITIVE);

        mvc.perform(publish(release, key, session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_STEP_UP_REQUIRED"));
        assertThat(records(key)).isZero();
        assertThat(published(store)).isZero();

        mvc.perform(MfaFixtures.stepUp(session, mfa.nextCode(publisher.secret()))).andExpect(status().isOk());
        String first = mvc.perform(publish(release, key, session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(publish(release, key, session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(records(key)).isEqualTo(1);
        assertThat(published(store)).isEqualTo(1);
    }

    private static MockHttpServletRequestBuilder publish(UUID release, String key, Cookie session) {
        return post(Publications.RELEASES + "/" + release + "/publish").cookie(session).with(csrf())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private long records(String key) {
        return jdbc.sql("SELECT count(*) FROM shared.idempotency_record WHERE key = ? AND status = 'COMPLETED'")
                .param(key)
                .query(Long.class).single();
    }

    private long published(UUID store) {
        return jdbc.sql("SELECT count(*) FROM catalog.configuration_release WHERE store_id = ? AND status = "
                + "'PUBLISHED'").param(store).query(Long.class).single();
    }
}
