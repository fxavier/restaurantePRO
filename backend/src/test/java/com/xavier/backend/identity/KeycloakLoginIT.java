package com.xavier.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.JWTParser;
import com.xavier.backend.SharedKeycloak;
import com.xavier.backend.access.Permission;
import com.xavier.backend.config.security.Members;
import com.xavier.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A85 against the real identity provider (D176, D189): the shared Keycloak 26 Testcontainer with the realms of
 * {@code deploy/keycloak}, and the backend trusting its issuers and fetching its key set lazily. A user of the tenant
 * realm logs in at Keycloak through the {@code backoffice-bff} client; the backend accepts the access token (signature
 * from the realm's JWKS, the audience mapper's {@code restaurantpro-api}, {@code azp backoffice-bff},
 * {@code typ Bearer}) and resolves the user's membership, refuses Keycloak's ID and refresh tokens and a tampered
 * token, and answers a sensitive action of this password-only login ({@code acr 1}) with the RFC 9470 challenge.
 *
 * <p>Test-only changes on the throw-away container, through the admin API: the BFF client may use the password grant
 * with a client secret (in production it uses the authorization code flow with {@code private_key_jwt}), and one user.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(Members.CloseTenantsAfterClass.class)
class KeycloakLoginIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String REALM = "restaurantpro";
    static final String CLIENT_SECRET = "it-only-" + UUID.randomUUID();
    static final String USERNAME = "it-" + UUID.randomUUID().toString().substring(0, 8);
    static final String PASSWORD = "Pw-" + UUID.randomUUID();

    @DynamicPropertySource
    static void realKeycloak(DynamicPropertyRegistry registry) {
        // its own context (not @SecurityHttpTest, whose test issuers would compete with these): the real realms
        registry.add("restaurantpro.test.jwt", () -> "false");
        registry.add("restaurantpro.bootstrap.company.name", () -> "Test Company");
        registry.add("restaurantpro.bootstrap.company.tax-id", () -> "TEST-TAX-ID");
        registry.add("restaurantpro.bootstrap.company.country", () -> "MZ");
        registry.add("restaurantpro.bootstrap.company.currency", () -> "MZN");
        registry.add("restaurantpro.security.issuers.tenant", () -> SharedKeycloak.instance().issuer(REALM));
        registry.add("restaurantpro.security.issuers.ops",
                () -> SharedKeycloak.instance().issuer("restaurantpro-ops"));
    }

    @Autowired
    MockMvc mvc;

    static JsonNode tokens;
    static String subject;

    @BeforeAll
    static void aUserLogsInAtKeycloak() throws Exception {
        String admin = adminToken();
        JsonNode client = JSON.readTree(adminCall(admin, "GET", "/clients?clientId=backoffice-bff", null).body())
                .get(0);
        Map<String, Object> passwordGrant = JSON.convertValue(client, Map.class);
        passwordGrant.put("directAccessGrantsEnabled", true);
        passwordGrant.put("clientAuthenticatorType", "client-secret");
        passwordGrant.put("secret", CLIENT_SECRET);
        assertThat(adminCall(admin, "PUT", "/clients/" + client.get("id").asString(), passwordGrant).statusCode())
                .isEqualTo(204);
        assertThat(adminCall(admin, "POST", "/users", Map.of("username", USERNAME, "enabled", true,
                "email", USERNAME + "@it.example", "emailVerified", true, "firstName", "It", "lastName", "User",
                "credentials", java.util.List.of(Map.of("type", "password", "value", PASSWORD,
                        "temporary", false)))).statusCode()).isEqualTo(201);

        HttpResponse<String> login = HTTP.send(HttpRequest.newBuilder(URI.create(
                        SharedKeycloak.instance().issuer(REALM) + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(form(Map.of("grant_type", "password", "client_id", "backoffice-bff",
                        "client_secret", CLIENT_SECRET, "username", USERNAME, "password", PASSWORD,
                        "scope", "openid"))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        tokens = JSON.readTree(login.body());
        subject = JWTParser.parse(tokens.get("access_token").asString()).getJWTClaimsSet().getSubject();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void theAccessTokenOfAMemberResolvesItsAccount() throws Exception {
        UUID tenant = Members.activeTenant();
        UUID account = member(tenant);

        mvc.perform(get("/api/v1/auth/session").header("Authorization", bearer(tokens.get("access_token")
                        .asString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(account.toString()));
    }

    @Test
    void anIdTokenAndARefreshTokenAreNeverAccessTokens() throws Exception {
        mvc.perform(get("/api/v1/auth/tenants").header("Authorization", bearer(tokens.get("id_token").asString())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/tenants").header("Authorization",
                        bearer(tokens.get("refresh_token").asString())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTamperedTokenIsRefused() throws Exception {
        String token = tokens.get("access_token").asString();
        String[] parts = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace(subject, UUID.randomUUID().toString());
        String forged = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        mvc.perform(get("/api/v1/auth/tenants").header("Authorization", bearer(forged)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aPasswordOnlyLoginGetsTheStepUpChallengeOnASensitiveAction() throws Exception {
        UUID tenant = Members.activeTenant();
        member(tenant, Members.role(tenant, Permission.SUPPORT_ACCESS_MANAGE));

        mvc.perform(post("/api/v1/backoffice/support-grants")
                        .header("Authorization", bearer(tokens.get("access_token").asString()))
                        .header("X-Tenant-Id", tenant.toString())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId": "%s", "subject": "ops-x", "durationMinutes": 60, "reason": "it"}"""
                                .formatted(Members.account(tenant, true))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", Matchers.containsString(
                        "error=\"insufficient_user_authentication\"")))
                .andExpect(jsonPath("$.code").value("MFA_STEP_UP_REQUIRED"));
    }

    /** An account of {@code tenant} whose ACCOUNT membership is the Keycloak user's, under the real issuer. */
    private static UUID member(UUID tenant, UUID... roles) {
        UUID account = Members.account(tenant, roles);
        Members.membership(tenant, SharedKeycloak.instance().issuer(REALM), subject, "ACCOUNT", account,
                java.time.Instant.now().minusSeconds(60), null);
        return account;
    }

    private static String adminToken() throws Exception {
        var keycloak = SharedKeycloak.instance().container();
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(keycloak.getAuthServerUrl()
                        .replaceAll("/$", "") + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(form(Map.of("grant_type", "password", "client_id", "admin-cli",
                        "username", keycloak.getAdminUsername(), "password", keycloak.getAdminPassword())))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("access_token").asString();
    }

    private static HttpResponse<String> adminCall(String token, String method, String path, Object body)
            throws Exception {
        String base = SharedKeycloak.instance().container().getAuthServerUrl().replaceAll("/$", "");
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + "/admin/realms/" + REALM + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.BodyPublisher form(Map<String, String> fields) {
        return HttpRequest.BodyPublishers.ofString(fields.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&")));
    }
}
