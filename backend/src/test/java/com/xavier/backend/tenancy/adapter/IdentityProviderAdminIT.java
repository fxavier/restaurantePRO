package com.xavier.backend.tenancy.adapter;

import static com.xavier.backend.tenancy.adapter.KeycloakProvisioners.DEVICE_REALM;
import static com.xavier.backend.tenancy.adapter.KeycloakProvisioners.OPS_REALM;
import static com.xavier.backend.tenancy.adapter.KeycloakProvisioners.TENANT_REALM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.tenancy.IdentityProviderAdmin;
import com.xavier.backend.tenancy.exception.IdentityProviderUnavailableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * The {@code RestClient} identity-provider adapter on a real Keycloak 26.x (D181, D182) and the O1 evidence for
 * option 3 (a dedicated devices realm): {@code device-provisioner} creates, keys and disables {@code pos-*}/
 * {@code bridge-*} clients in {@code restaurantpro-devices} and can change nothing else — no other realm, not its own
 * realm's settings or flows, no role mapping; {@code tenancy-provisioner} creates organizations and invites users in
 * {@code restaurantpro} (FGAP v2, Users and Organizations only) and cannot touch any client, the realm settings or the
 * flows. A terminal's client gets devices-realm tokens for the API until it is disabled (D176 consequence for S73).
 */
class IdentityProviderAdminIT {

    static final String POS_ORIGIN = "https://pos.restaurantpro.test";

    static KeycloakProvisioners keycloak;
    static IdentityProviderAdmin idp;

    @BeforeAll
    static void provisioners() throws Exception {
        keycloak = KeycloakProvisioners.instance();
        idp = new KeycloakIdentityProviderAdmin(realm(keycloak.tenantProvisioner), realm(keycloak.deviceProvisioner),
                POS_ORIGIN);
    }

    @Test
    void theDeviceProvisionerCreatesKeysAndDisablesTerminalClientsIdempotently() throws Exception {
        String clientId = "pos-" + UUID.randomUUID();
        Path key = KeycloakProvisioners.newKey(clientId);

        String serviceAccount = idp.createClient(clientId);
        idp.registerClientKey(clientId, Files.readString(Path.of(key + ".jwk")));

        assertThat(idp.createClient(clientId)).as("idempotent").isEqualTo(serviceAccount);
        Map<String, Object> client = keycloak.client(DEVICE_REALM, clientId);
        assertThat(client).containsEntry("enabled", true).containsEntry("clientAuthenticatorType", "client-jwt")
                .containsEntry("serviceAccountsEnabled", true).containsEntry("standardFlowEnabled", false)
                .containsEntry("publicClient", false);
        assertThat((Map<String, Object>) client.get("attributes")).containsEntry("use.jwks.string", "true")
                .extractingByKey("jwks.string").asString().contains(clientId);
        Map<String, Object> claims = claims(realm(new KeycloakAdminClient(keycloak.baseUrl, DEVICE_REALM, clientId,
                key.toString())).accessToken());
        assertThat(claims).containsEntry("iss", keycloak.baseUrl + "/realms/" + DEVICE_REALM)
                .containsEntry("sub", serviceAccount).containsEntry("azp", clientId);
        assertThat(claims.get("aud").toString()).contains("restaurantpro-api");

        idp.disableClient(clientId);
        idp.disableClient(clientId);

        assertThat(keycloak.client(DEVICE_REALM, clientId)).containsEntry("enabled", false);
        assertThatThrownBy(() -> realm(new KeycloakAdminClient(keycloak.baseUrl, DEVICE_REALM, clientId,
                key.toString())).accessToken()).as("the next token request fails at once")
                .isInstanceOf(IdentityProviderUnavailableException.class);
    }

    /**
     * G13 (ADR 0041): the POS signs its RFC 7523 assertion in the browser and calls the devices realm's token endpoint
     * from the POS origin, so Keycloak must approve that origin, and only it, for a terminal's client.
     */
    @Test
    void aTerminalClientApprovesBrowserTokenRequestsFromThePosOriginOnly() throws Exception {
        String clientId = "pos-" + UUID.randomUUID();
        Path key = KeycloakProvisioners.newKey(clientId);
        idp.createClient(clientId);
        idp.registerClientKey(clientId, Files.readString(Path.of(key + ".jwk")));

        ResponseEntity<String> fromPos = browserTokenRequest(clientId, key, POS_ORIGIN);
        ResponseEntity<String> fromElsewhere = browserTokenRequest(clientId, key, "https://evil.example");

        assertThat(fromPos.getStatusCode().value()).as(fromPos.getBody()).isEqualTo(200);
        assertThat(fromPos.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo(POS_ORIGIN);
        assertThat(fromElsewhere.getStatusCode().value()).as("another origin").isEqualTo(403);
        assertThat(fromElsewhere.getHeaders().getFirst("Access-Control-Allow-Origin")).as("another origin").isNull();
        assertThat((List<String>) keycloak.client(DEVICE_REALM, clientId).get("webOrigins"))
                .containsExactly(POS_ORIGIN);
    }

    @Test
    void aBridgeClientHasNoWebOrigin() throws Exception {
        String clientId = "bridge-" + UUID.randomUUID();
        Path key = KeycloakProvisioners.newKey(clientId);
        idp.createClient(clientId);
        idp.registerClientKey(clientId, Files.readString(Path.of(key + ".jwk")));

        assertThat((List<String>) keycloak.client(DEVICE_REALM, clientId).get("webOrigins")).isEmpty();
        assertThat(browserTokenRequest(clientId, key, POS_ORIGIN).getHeaders()
                .getFirst("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void withoutAPosOriginTerminalClientsAreRefusedAndBridgesAreNot() {
        IdentityProviderAdmin noPosOrigin = new KeycloakIdentityProviderAdmin(realm(keycloak.tenantProvisioner),
                realm(keycloak.deviceProvisioner), "");
        String terminal = "pos-" + UUID.randomUUID();

        assertThatThrownBy(() -> noPosOrigin.createClient(terminal))
                .isInstanceOf(IdentityProviderUnavailableException.class);
        assertThat(keycloak.clientUuid(DEVICE_REALM, terminal)).as("nothing created").isNull();
        assertThat(noPosOrigin.createClient("bridge-" + UUID.randomUUID())).isNotBlank();
    }

    @Test
    void enrolmentSetsTheCurrentPosOrigin() throws Exception {
        String clientId = "pos-" + UUID.randomUUID();
        Path key = KeycloakProvisioners.newKey(clientId);
        idp.createClient(clientId);
        IdentityProviderAdmin moved = new KeycloakIdentityProviderAdmin(realm(keycloak.tenantProvisioner),
                realm(keycloak.deviceProvisioner), "https://pos2.restaurantpro.test:8443");

        moved.registerClientKey(clientId, Files.readString(Path.of(key + ".jwk")));

        assertThat((List<String>) keycloak.client(DEVICE_REALM, clientId).get("webOrigins"))
                .containsExactly("https://pos2.restaurantpro.test:8443");
    }

    @Test
    void aPosOriginThatIsNotAnOriginIsRefusedAtStartup() {
        for (String notAnOrigin : List.of("*", "+", "https://pos.example/", "https://pos.example/app", "pos.example")) {
            assertThatThrownBy(() -> new KeycloakIdentityProviderAdmin(realm(keycloak.tenantProvisioner),
                    realm(keycloak.deviceProvisioner), notAnOrigin)).as(notAnOrigin)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void onlyTerminalAndBridgeClientsAndOnlyPublicKeysAreAccepted() throws Exception {
        assertThatThrownBy(() -> idp.createClient("backoffice-bff")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> idp.disableClient("device-provisioner")).isInstanceOf(IllegalArgumentException.class);
        String clientId = "bridge-" + UUID.randomUUID();
        idp.createClient(clientId);
        String privateJwk = Files.readString(Path.of(KeycloakProvisioners.newKey(clientId) + ".jwk"))
                .replace("}", ",\"d\":\"AAAA\"}");

        assertThatThrownBy(() -> idp.registerClientKey(clientId, privateJwk)).as("a private key is never stored")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> idp.registerClientKey(clientId, "{\"kty\":\"oct\",\"k\":\"AAAA\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDeviceProvisionerCannotChangeAnythingButDeviceClients() {
        String token = realm(keycloak.deviceProvisioner).accessToken();
        String pos = "pos-" + UUID.randomUUID();
        idp.createClient(pos);
        Map<?, ?> manageRealm = Map.of("name", "manage-realm");

        assertThat(code(HttpMethod.GET, "/admin/realms/" + TENANT_REALM + "/clients", token, null))
                .as("the tenant realm").isIn(401, 403);
        assertThat(code(HttpMethod.GET, "/admin/realms/" + OPS_REALM + "/clients", token, null)).as("the ops realm")
                .isIn(401, 403);
        assertThat(code(HttpMethod.PUT, "/admin/realms/" + DEVICE_REALM, token, Map.of("displayName", "x")))
                .as("its realm's settings").isEqualTo(403);
        // reading the flows is allowed to client managers (a client's flow override names one); changing is not
        assertThat(code(HttpMethod.POST, "/admin/realms/" + DEVICE_REALM + "/authentication/flows", token,
                Map.of("alias", "evil-" + UUID.randomUUID(), "providerId", "basic-flow", "topLevel", true,
                        "builtIn", false))).as("create a flow").isEqualTo(403);
        assertThat(code(HttpMethod.POST, "/admin/realms/" + DEVICE_REALM + "/authentication/flows/browser/copy",
                token, Map.of("newName", "evil-" + UUID.randomUUID()))).as("copy a flow").isEqualTo(403);
        assertThat(code(HttpMethod.POST, "/admin/realms/" + DEVICE_REALM + "/users", token,
                Map.of("username", "u" + UUID.randomUUID()))).as("users").isEqualTo(403);
        String serviceAccount = idp.createClient(pos);
        assertThat(code(HttpMethod.POST, "/admin/realms/" + DEVICE_REALM + "/users/" + serviceAccount
                + "/role-mappings/clients/" + keycloak.clientUuid(DEVICE_REALM, "realm-management"), token,
                List.of(manageRealm))).as("a role mapping").isEqualTo(403);
    }

    @Test
    void theTenancyProvisionerCreatesOrganizationsAndInvitesUsersIdempotently() {
        String alias = "acme-" + UUID.randomUUID().toString().substring(0, 8);
        String email = "admin@" + alias + ".example";

        String organization = idp.createOrganization(alias, "Acme Lda");
        String user = idp.inviteUser(email, organization);

        assertThat(idp.createOrganization(alias, "Acme Lda")).isEqualTo(organization);
        assertThat(idp.inviteUser(email, organization)).isEqualTo(user);
        assertThat(keycloak.mailRecipients()).contains(email);
        assertThat(idp.userSessions(user)).isEmpty();
        idp.logoutUser(user);
        idp.addOrganizationMember(organization, user);
    }

    @Test
    void theTenancyProvisionerCannotTouchClientsRealmSettingsOrFlows() {
        String token = realm(keycloak.tenantProvisioner).accessToken();
        String clients = "/admin/realms/" + TENANT_REALM + "/clients";
        String bff = keycloak.clientUuid(TENANT_REALM, "backoffice-bff");
        String api = keycloak.clientUuid(TENANT_REALM, "restaurantpro-api");

        assertThat(code(HttpMethod.GET, clients, token, null)).as("list clients").isEqualTo(403);
        assertThat(code(HttpMethod.POST, clients, token, Map.of("clientId", "pos-" + UUID.randomUUID())))
                .as("create a client").isEqualTo(403);
        assertThat(code(HttpMethod.PUT, clients + "/" + bff, token, Map.of("redirectUris", List.of("https://x/*"))))
                .as("change backoffice-bff").isEqualTo(403);
        assertThat(code(HttpMethod.POST, clients + "/" + api + "/roles", token, Map.of("name", "evil")))
                .as("another client's roles").isEqualTo(403);
        assertThat(code(HttpMethod.PUT, "/admin/realms/" + TENANT_REALM, token, Map.of("displayName", "x")))
                .as("realm settings").isEqualTo(403);
        assertThat(code(HttpMethod.GET, "/admin/realms/" + TENANT_REALM + "/authentication/flows", token, null))
                .as("flows").isEqualTo(403);
        assertThat(code(HttpMethod.GET, "/admin/realms/" + DEVICE_REALM + "/clients", token, null))
                .as("the devices realm").isIn(401, 403);
    }

    @Test
    void anUnconfiguredRealmFailsClosed() {
        IdentityProviderAdmin unconfigured = new KeycloakIdentityProviderAdmin(
                realm(new KeycloakAdminClient("", TENANT_REALM, "tenancy-provisioner", "")),
                realm(new KeycloakAdminClient(keycloak.baseUrl, DEVICE_REALM, "device-provisioner", null)), POS_ORIGIN);

        assertThatThrownBy(() -> unconfigured.createOrganization("x", "X"))
                .isInstanceOf(IdentityProviderUnavailableException.class);
        assertThatThrownBy(() -> unconfigured.createClient("pos-" + UUID.randomUUID()))
                .isInstanceOf(IdentityProviderUnavailableException.class);
    }

    /** What a browser on {@code origin} sends: client credentials, a signed assertion and its {@code Origin}. */
    private static ResponseEntity<String> browserTokenRequest(String clientId, Path key, String origin) {
        KeycloakAdminClient device = new KeycloakAdminClient(keycloak.baseUrl, DEVICE_REALM, clientId, key.toString());
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        form.add("client_assertion", realm(device).assertion());
        return RestClient.create().post().uri(device.issuer() + "/protocol/openid-connect/token")
                .header("Origin", origin).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                        .headers(response.getHeaders()).body(new String(response.getBody().readAllBytes())));
    }

    private static RealmAdmin realm(KeycloakAdminClient client) {
        return new RealmAdmin(RestClient.builder(), client, Clock.systemUTC());
    }

    private static int code(HttpMethod method, String path, String token, Object body) {
        return keycloak.status(method, path, token, body).value();
    }

    private static Map<String, Object> claims(String token) {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return tools.jackson.databind.json.JsonMapper.builder().build().readValue(payload, Map.class);
    }
}
