package com.xavier.backend.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.identity.oidc.exception.OidcUnavailableException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/** D144: discovery on first use, cached; while the IdP is unreachable, 503 OIDC_UNAVAILABLE and a retry next time. */
class LazyClientRegistrationRepositoryTest {

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

        new LazyClientRegistrationRepository(properties(idp.issuer(), "src/test/resources/identity/oidc-client-secret.txt"));

        assertThat(idp.requests()).isEmpty();
    }

    @Test
    void theFirstLookupDiscoversTheProviderAndLaterOnesUseTheCache() {
        idp = StubIdp.start();
        var repository = new LazyClientRegistrationRepository(
                properties(idp.issuer(), "src/test/resources/identity/oidc-client-secret.txt"));

        ClientRegistration registration = repository.findByRegistrationId(LazyClientRegistrationRepository.REGISTRATION_ID);
        repository.findByRegistrationId(LazyClientRegistrationRepository.REGISTRATION_ID);

        assertThat(idp.requests()).containsExactly("/realm/.well-known/openid-configuration");
        assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(idp.issuer());
        assertThat(registration.getProviderDetails().getTokenUri()).isEqualTo(idp.issuer() + "/token");
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
        var repository = new LazyClientRegistrationRepository(properties(idp.issuer(), null));

        assertThat(repository.findByRegistrationId(LazyClientRegistrationRepository.REGISTRATION_ID)
                .getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
    }

    @Test
    void anotherRegistrationIdIsUnknownAndCallsNobody() {
        idp = StubIdp.start();
        var repository = new LazyClientRegistrationRepository(properties(idp.issuer(), null));

        assertThat(repository.findByRegistrationId("google")).isNull();
        assertThat(idp.requests()).isEmpty();
    }

    @Test
    void anUnreachableProviderIsUnavailableAndRetriedOnTheNextLookup() {
        idp = StubIdp.stopped();
        var repository = new LazyClientRegistrationRepository(properties(idp.issuer(), null));

        assertThatThrownBy(() -> repository.findByRegistrationId(LazyClientRegistrationRepository.REGISTRATION_ID))
                .isInstanceOf(OidcUnavailableException.class)
                .extracting(e -> ((OidcUnavailableException) e).code()).isEqualTo("OIDC_UNAVAILABLE");

        idp.restart();

        assertThat(repository.findByRegistrationId(LazyClientRegistrationRepository.REGISTRATION_ID)).isNotNull();
    }

    private static OidcProperties properties(String issuer, String secretFile) {
        return new OidcProperties(true, issuer, StubIdp.CLIENT_ID, secretFile, null, List.of(), false, null);
    }
}
