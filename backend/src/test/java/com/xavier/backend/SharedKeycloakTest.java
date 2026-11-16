package com.xavier.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D189: the shared Keycloak of the identity and end-to-end suites starts (pinned 26.x) with every realm of
 * {@code deploy/keycloak} imported, each serving its OpenID discovery document under its own issuer.
 */
class SharedKeycloakTest {

    @Test
    void theSharedKeycloakStartsWithEveryRealmOfDeployKeycloak() throws Exception {
        SharedKeycloak keycloak = SharedKeycloak.instance();
        List<String> realms = SharedKeycloak.realmNames();
        assertThat(realms).contains("restaurantpro", "restaurantpro-ops");

        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            for (String realm : realms) {
                String issuer = keycloak.issuer(realm);
                HttpResponse<String> discovery = http.send(HttpRequest.newBuilder(
                        URI.create(issuer + "/.well-known/openid-configuration")).build(),
                        HttpResponse.BodyHandlers.ofString());

                assertThat(discovery.statusCode()).as(realm).isEqualTo(200);
                assertThat(discovery.body()).as(realm).contains("\"issuer\":\"" + issuer + "\"");
            }
        }
    }
}
