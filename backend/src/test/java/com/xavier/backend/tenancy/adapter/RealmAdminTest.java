package com.xavier.backend.tenancy.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.xavier.backend.tenancy.exception.IdentityProviderUnavailableException;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * G5: a token the realm refuses (401: revoked, its signing key rotated, a realm restored) is dropped at once, so the
 * next call asks for a new one instead of failing with the cached token until it expires.
 */
class RealmAdminTest {

    static final String BASE = "http://keycloak.test";
    static final String TOKEN_URL = BASE + "/realms/r/protocol/openid-connect/token";
    static final String CLIENTS_URL = BASE + "/admin/realms/r/clients";

    @Test
    void aRefusedTokenIsDroppedSoTheNextCallGetsANewOne() throws Exception {
        RestClient.Builder http = RestClient.builder();
        MockRestServiceServer keycloak = MockRestServiceServer.bindTo(http).build();
        Path key = KeycloakProvisioners.newKey("realm-admin-test");
        RealmAdmin realm = new RealmAdmin(http, new KeycloakAdminClient(BASE, "r", "c", key.toString()),
                Clock.systemUTC());
        keycloak.expect(requestTo(TOKEN_URL)).andRespond(token("first"));
        keycloak.expect(requestTo(CLIENTS_URL)).andExpect(header("Authorization", "Bearer first"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        keycloak.expect(requestTo(TOKEN_URL)).andRespond(token("second"));
        keycloak.expect(requestTo(CLIENTS_URL)).andExpect(header("Authorization", "Bearer second"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> realm.send(HttpMethod.GET, "/clients", null))
                .isInstanceOf(IdentityProviderUnavailableException.class);

        assertThat(realm.send(HttpMethod.GET, "/clients", null).status()).isEqualTo(200);
        keycloak.verify();
    }

    private static org.springframework.test.web.client.ResponseCreator token(String value) {
        return withSuccess("{\"access_token\":\"" + value + "\",\"expires_in\":300}", MediaType.APPLICATION_JSON);
    }
}
