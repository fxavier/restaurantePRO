package com.xavier.backend.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.MfaTest;
import com.xavier.backend.identity.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A61 / §11: OIDC enabled and the IdP unreachable from the start. The backend starts (discovery is lazy), the OIDC
 * routes answer 503 {@code OIDC_UNAVAILABLE}, password login works (P56), a black-hole IdP holds no request long,
 * and the IdP is retried 30 s after a failure.
 */
@MfaTest
@ContextConfiguration(initializers = OidcIdpDownTest.Down.class)
class OidcIdpDownTest {

    static final StubIdp IDP = StubIdp.stopped();

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @AfterAll
    static void stop() {
        IDP.stop();
    }

    @Test
    void theBackendStartsAuthorizeIs503AndPasswordLoginWorksThenTheIdpIsRetried() throws Exception {
        expectUnavailable();
        mvc.perform(get(OidcRoutes.BASE)).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));

        IdentityFixtures identity = new IdentityFixtures(mvc);
        IdentityFixtures.User user = identity.user(Set.of(IdentityFixtures.MANAGER), null);
        mvc.perform(IdentityFixtures.loginRequest(user.username(), IdentityFixtures.STRONG_PASSWORD))
                .andExpect(status().isOk());

        // the IdP is back, but the failure is remembered for 30 s: 503 at once, no call
        IDP.restart();
        expectUnavailable();
        assertThat(IDP.requests()).isEmpty();

        // S57 review: an IdP that accepts and never answers holds no request long, and requests do not queue
        IDP.blackHole();
        clock.advance(LazyClientRegistrationRepository.FAILURE_MEMORY);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            long started = System.nanoTime();
            List<Future<Integer>> calls = pool.invokeAll(List.<Callable<Integer>>of(this::authorizeStatus,
                    this::authorizeStatus, this::authorizeStatus), 30, TimeUnit.SECONDS);
            for (Future<Integer> call : calls) {
                assertThat(call.get()).isEqualTo(503);
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        } finally {
            pool.shutdownNow();
        }
        long again = System.nanoTime();
        expectUnavailable();
        assertThat(Duration.ofNanos(System.nanoTime() - again)).isLessThan(Duration.ofSeconds(1));

        IDP.answer();
        clock.advance(LazyClientRegistrationRepository.FAILURE_MEMORY);
        mvc.perform(get(OidcRoutes.AUTHORIZE)).andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(IDP.issuer() + "/authorize?")));
    }

    private void expectUnavailable() throws Exception {
        mvc.perform(get(OidcRoutes.AUTHORIZE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("OIDC_UNAVAILABLE"));
    }

    private Integer authorizeStatus() throws Exception {
        return mvc.perform(get(OidcRoutes.AUTHORIZE)).andReturn().getResponse().getStatus();
    }

    static class Down implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "restaurantpro.security.oidc.enabled=true",
                    "restaurantpro.security.oidc.issuer-uri=" + IDP.issuer(),
                    "restaurantpro.security.oidc.client-id=" + StubIdp.CLIENT_ID
            ).applyTo(context);
        }
    }
}
