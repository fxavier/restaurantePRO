package com.xavier.backend.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.identity.MutableClock;
import com.xavier.backend.identity.oidc.exception.OidcUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * D144: discovery on first use, cached. S57 review: never a long wait while the IdP is unreachable or a black hole
 * (timeouts, no lock), a failure answered at once for 30 s, then retried; the announced issuer must be the configured
 * one.
 */
class LazyClientRegistrationRepositoryTest {

    private static final String ID = LazyClientRegistrationRepository.REGISTRATION_ID;

    private final MutableClock clock = new MutableClock();
    private StubIdp idp;

    @AfterEach
    void stop() {
        if (idp != null) {
            idp.close();
        }
    }

    @Test
    void creatingItCallsNobody() {
        idp = StubIdp.start();

        repository(idp.issuer(), "src/test/resources/identity/oidc-client-secret.txt");

        assertThat(idp.requests()).isEmpty();
    }

    @Test
    void theFirstLookupDiscoversTheProviderAndLaterOnesUseTheCache() {
        idp = StubIdp.start();
        var repository = repository(idp.issuer(), "src/test/resources/identity/oidc-client-secret.txt");

        ClientRegistration registration = repository.findByRegistrationId(ID);
        repository.findByRegistrationId(ID);

        assertThat(idp.requests()).containsExactly("/realm/.well-known/openid-configuration");
        assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(idp.issuer());
        assertThat(registration.getProviderDetails().getTokenUri()).isEqualTo(idp.issuer() + "/token");
        assertThat(registration.getProviderDetails().getJwkSetUri()).isEqualTo(idp.issuer() + "/jwks");
        assertThat(registration.getClientId()).isEqualTo(StubIdp.CLIENT_ID);
        assertThat(registration.getClientSecret()).isEqualTo(StubIdp.CLIENT_SECRET);
        assertThat(registration.getClientAuthenticationMethod())
                .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(registration.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(registration.getRedirectUri()).isEqualTo("{baseUrl}/api/v1/auth/oidc/callback");
        assertThat(registration.getScopes()).containsExactly("openid");
        assertThat(registration.getClientSettings().isRequireProofKey()).isTrue();
    }

    @Test
    void withoutASecretItIsAPublicClient() {
        idp = StubIdp.start();

        assertThat(repository(idp.issuer(), null).findByRegistrationId(ID).getClientAuthenticationMethod())
                .isEqualTo(ClientAuthenticationMethod.NONE);
    }

    @Test
    void anotherRegistrationIdIsUnknownAndCallsNobody() {
        idp = StubIdp.start();

        assertThat(repository(idp.issuer(), null).findByRegistrationId("google")).isNull();
        assertThat(idp.requests()).isEmpty();
    }

    @Test
    void aFailureIsAnsweredAtOnceFor30SecondsThenRetried() {
        idp = StubIdp.stopped();
        var repository = repository(idp.issuer(), null);
        assertUnavailable(repository);

        idp.restart();
        clock.advance(LazyClientRegistrationRepository.FAILURE_MEMORY.minusSeconds(1));
        assertUnavailable(repository);
        assertThat(idp.requests()).isEmpty();

        clock.advance(Duration.ofSeconds(1));
        assertThat(repository.findByRegistrationId(ID)).isNotNull();
    }

    @Test
    void aDocumentAnnouncingAnotherIssuerIsRefused() {
        idp = StubIdp.start();
        idp.announceIssuer(idp.issuer() + "/");

        assertUnavailable(repository(idp.issuer(), null));
    }

    @Test
    void aBlackHoleProviderTimesOutAndConcurrentLookupsDoNotQueue() throws Exception {
        idp = StubIdp.start();
        idp.blackHole();
        var repository = repository(idp.issuer(), null);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            long started = System.nanoTime();
            List<Future<Throwable>> lookups = pool.invokeAll(List.<Callable<Throwable>>of(
                    () -> failure(repository), () -> failure(repository), () -> failure(repository)),
                    20, java.util.concurrent.TimeUnit.SECONDS); // without timeouts they would wait forever: cancelled
            for (Future<Throwable> lookup : lookups) {
                assertThat(lookup.get()).isInstanceOf(OidcUnavailableException.class);
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            // one read timeout (5 s), not three in a row
            assertThat(elapsed).isLessThan(OidcHttp.READ_TIMEOUT.multipliedBy(2));

            long again = System.nanoTime();
            assertUnavailable(repository);
            assertThat(Duration.ofNanos(System.nanoTime() - again)).isLessThan(Duration.ofSeconds(1));
        } finally {
            pool.shutdownNow();
        }
    }

    private LazyClientRegistrationRepository repository(String issuer, String secretFile) {
        return new LazyClientRegistrationRepository(
                new OidcProperties("true", issuer, StubIdp.CLIENT_ID, secretFile, null, List.of(), false, null), clock);
    }

    private static void assertUnavailable(LazyClientRegistrationRepository repository) {
        assertThatThrownBy(() -> repository.findByRegistrationId(ID))
                .isInstanceOf(OidcUnavailableException.class)
                .extracting(e -> ((OidcUnavailableException) e).code()).isEqualTo("OIDC_UNAVAILABLE");
    }

    private static Throwable failure(LazyClientRegistrationRepository repository) {
        try {
            repository.findByRegistrationId(ID);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }
}
