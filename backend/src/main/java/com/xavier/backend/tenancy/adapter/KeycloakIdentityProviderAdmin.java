package com.xavier.backend.tenancy.adapter;

import com.xavier.backend.tenancy.IdentityProviderAdmin;
import com.xavier.backend.tenancy.exception.IdentityProviderUnavailableException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;

/**
 * The {@code RestClient} adapter of the identity-provider port (D181, D182; no admin client library), split by realm
 * as O1 option 3 decided: organizations, members, invitations and sessions in the tenant realm as
 * {@code tenancy-provisioner} (FGAP v2: Users and Organizations, no client right); terminal and bridge clients in the
 * dedicated {@code restaurantpro-devices} realm as {@code device-provisioner} ({@code manage-clients} there only).
 * Every operation is idempotent (a 409 finds what an earlier attempt created), so a D48 retry converges. Only
 * {@code pos-*} and {@code bridge-*} client ids are accepted, and only a public EC P-256 key is ever registered.
 * A {@code pos-*} client's one web origin is the POS origin (G13, ADR 0041: the browser calls the token endpoint);
 * a {@code bridge-*} client has none. Without a POS origin, terminal clients are refused (503 {@code IDP_UNAVAILABLE}).
 */
class KeycloakIdentityProviderAdmin implements IdentityProviderAdmin {

    static final List<String> INVITE_ACTIONS = List.of("VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP");
    static final String API_AUDIENCE = "restaurantpro-api";
    private static final Set<String> PUBLIC_JWK_MEMBERS = Set.of("kty", "crv", "x", "y", "kid", "use", "alg");
    /** Scheme, host and optional port: no path, no wildcard (a Keycloak "*" or "+" would widen the approval). */
    private static final Pattern ORIGIN = Pattern.compile("(https?)://([a-z0-9.-]+|\\[::1\\])(:[0-9]{1,5})?");
    /** Plain HTTP only where no network sits between the browser and the POS (local development). */
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityProviderAdmin.class);

    private final RealmAdmin tenantRealm;
    private final RealmAdmin deviceRealm;
    private final String posOrigin;

    /** @param posOrigin the POS app's origin ({@code restaurantpro.pos.origin}); blank = terminals cannot be created */
    KeycloakIdentityProviderAdmin(RealmAdmin tenantRealm, RealmAdmin deviceRealm, String posOrigin) {
        this.tenantRealm = tenantRealm;
        this.deviceRealm = deviceRealm;
        this.posOrigin = posOrigin(posOrigin);
        if (this.posOrigin == null) {
            log.warn("restaurantpro.pos.origin is not set: terminal enrolment will answer 503 until it is");
        }
    }

    /**
     * Lowercased, because browsers send the origin lowercased and Keycloak compares web origins exactly.
     *
     * @return the origin to approve, or null when unset
     * @throws IllegalArgumentException not an origin, or plain HTTP off the loopback: the startup fails
     */
    static String posOrigin(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String origin = configured.toLowerCase(Locale.ROOT);
        Matcher parts = ORIGIN.matcher(origin);
        if (!parts.matches()) {
            throw new IllegalArgumentException("restaurantpro.pos.origin is not an origin (scheme://host[:port]): "
                    + configured);
        }
        if (parts.group(1).equals("http") && !LOOPBACK.contains(parts.group(2))) {
            throw new IllegalArgumentException("restaurantpro.pos.origin must use https off the loopback: "
                    + configured);
        }
        return origin;
    }

    /** The organization's name and alias are the tenant code (unique); its description is the legal name. */
    @Override
    public String createOrganization(String alias, String name) {
        RealmAdmin.Reply reply = tenantRealm.send(HttpMethod.POST, "/organizations", Map.of("name", alias,
                "alias", alias, "description", name, "enabled", true));
        if (reply.status() == 201) {
            return reply.createdId();
        }
        expect(reply, 409, "create organization");
        List<Map<String, Object>> found = tenantRealm.read("/organizations?exact=true&search=" + encode(alias),
                List.class);
        return found.stream().filter(organization -> alias.equals(organization.get("alias")))
                .map(organization -> (String) organization.get("id")).findFirst()
                .orElseThrow(() -> new IllegalStateException("Organization " + alias + " conflicts but is not found"));
    }

    @Override
    public void addOrganizationMember(String organizationId, String userId) {
        RealmAdmin.Reply reply = tenantRealm.send(HttpMethod.POST, "/organizations/" + organizationId + "/members",
                userId);
        if (reply.status() != 201) {
            expect(reply, 409, "add organization member");
        }
    }

    @Override
    public String inviteUser(String email, String organizationId) {
        String userId = findUser(email).orElseGet(() -> createUser(email));
        addOrganizationMember(organizationId, userId);
        expect(tenantRealm.send(HttpMethod.PUT, "/users/" + userId + "/execute-actions-email", INVITE_ACTIONS), 204,
                "send the invitation");
        return userId;
    }

    @Override
    public List<IdentitySession> userSessions(String userId) {
        List<Map<String, Object>> sessions = tenantRealm.read("/users/" + userId + "/sessions", List.class);
        return sessions.stream().map(session -> new IdentitySession((String) session.get("id"),
                (String) session.get("ipAddress"), epochMillis(session.get("start")),
                epochMillis(session.get("lastAccess")))).toList();
    }

    @Override
    public void logoutUser(String userId) {
        expect(tenantRealm.send(HttpMethod.POST, "/users/" + userId + "/logout", null), 204, "log out");
    }

    @Override
    public String createClient(String clientId) {
        requireDeviceClient(clientId);
        RealmAdmin.Reply reply = deviceRealm.send(HttpMethod.POST, "/clients", deviceClient(clientId,
                webOrigins(clientId)));
        if (reply.status() != 201) {
            expect(reply, 409, "create client");
        }
        String uuid = clientUuid(clientId).orElseThrow(() -> new IllegalStateException(clientId + " not found"));
        Map<?, ?> serviceAccount = deviceRealm.read("/clients/" + uuid + "/service-account-user", Map.class);
        return (String) serviceAccount.get("id");
    }

    @Override
    public void registerClientKey(String clientId, String publicJwk) {
        requireDeviceClient(clientId);
        String jwk = RealmAdmin.JSON.writeValueAsString(publicKeyOnly(publicJwk));
        List<String> webOrigins = webOrigins(clientId);
        String uuid = clientUuid(clientId).orElseThrow(() -> new IllegalStateException(clientId + " not found"));
        // the origin is set again at enrolment: a client created before it (or before it changed) converges
        expect(deviceRealm.send(HttpMethod.PUT, "/clients/" + uuid, Map.of("webOrigins", webOrigins,
                "attributes", Map.of("use.jwks.string", "true", "use.jwks.url", "false",
                        "jwks.string", "{\"keys\":[" + jwk + "]}"))), 204, "register the client key");
    }

    @Override
    public void disableClient(String clientId) {
        requireDeviceClient(clientId);
        Optional<String> uuid = clientUuid(clientId);
        if (uuid.isEmpty()) {
            return;
        }
        expect(deviceRealm.send(HttpMethod.PUT, "/clients/" + uuid.get(), Map.of("enabled", false)), 204,
                "disable the client");
    }

    /** A terminal's client approves the POS origin only; a bridge is no browser and approves none. */
    private List<String> webOrigins(String clientId) {
        if (!clientId.startsWith("pos-")) {
            return List.of();
        }
        if (posOrigin == null) {
            log.error("restaurantpro.pos.origin is not set: terminal clients cannot be provisioned");
            throw new IdentityProviderUnavailableException();
        }
        return List.of(posOrigin);
    }

    private Optional<String> findUser(String email) {
        return firstId(tenantRealm, "/users?exact=true&email=" + encode(email));
    }

    private String createUser(String email) {
        RealmAdmin.Reply reply = tenantRealm.send(HttpMethod.POST, "/users", Map.of("username", email, "email", email,
                "enabled", true, "emailVerified", false, "requiredActions", INVITE_ACTIONS));
        if (reply.status() == 201) {
            return reply.createdId();
        }
        expect(reply, 409, "create user");
        return findUser(email).orElseThrow(() -> new IllegalStateException("A user conflicts but is not found"));
    }

    private Optional<String> clientUuid(String clientId) {
        return firstId(deviceRealm, "/clients?clientId=" + encode(clientId));
    }

    /** The id of the first representation an exact search answers (Keycloak lists matches). */
    private static Optional<String> firstId(RealmAdmin realm, String search) {
        List<Map<String, Object>> found = realm.read(search, List.class);
        return found.stream().map(representation -> (String) representation.get("id")).findFirst();
    }

    /** A confidential client with no secret: {@code private_key_jwt} (ES256), client credentials only, the API audience. */
    private static Map<String, Object> deviceClient(String clientId, List<String> webOrigins) {
        return Map.ofEntries(Map.entry("clientId", clientId), Map.entry("enabled", true),
                Map.entry("webOrigins", webOrigins),
                Map.entry("publicClient", false), Map.entry("clientAuthenticatorType", "client-jwt"),
                Map.entry("serviceAccountsEnabled", true), Map.entry("standardFlowEnabled", false),
                Map.entry("implicitFlowEnabled", false), Map.entry("directAccessGrantsEnabled", false),
                Map.entry("fullScopeAllowed", false),
                Map.entry("attributes", Map.of("token.endpoint.auth.signing.alg", "ES256")),
                Map.entry("protocolMappers", List.of(Map.of("name", "audience " + API_AUDIENCE,
                        "protocol", "openid-connect", "protocolMapper", "oidc-audience-mapper",
                        "config", Map.of("included.custom.audience", API_AUDIENCE, "access.token.claim", "true",
                                "id.token.claim", "false")))));
    }

    /** The device's public key, checked where it enters: EC P-256 with x and y, and no private member at all. */
    private static Map<String, Object> publicKeyOnly(String publicJwk) {
        Map<String, Object> jwk;
        try {
            jwk = RealmAdmin.JSON.readValue(publicJwk, Map.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The public key is not a JWK");
        }
        if (!"EC".equals(jwk.get("kty")) || !"P-256".equals(jwk.get("crv")) || !(jwk.get("x") instanceof String)
                || !(jwk.get("y") instanceof String) || !PUBLIC_JWK_MEMBERS.containsAll(jwk.keySet())) {
            throw new IllegalArgumentException("Only a public EC P-256 JWK is accepted");
        }
        return jwk;
    }

    private static void requireDeviceClient(String clientId) {
        if (clientId == null || !clientId.matches("(pos|bridge)-[A-Za-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Only pos-* and bridge-* clients are managed");
        }
    }

    private static void expect(RealmAdmin.Reply reply, int status, String action) {
        if (reply.status() != status) {
            throw new IllegalStateException("Keycloak answered " + reply.status() + " to " + action);
        }
    }

    private static Instant epochMillis(Object value) {
        return value instanceof Number millis ? Instant.ofEpochMilli(millis.longValue()) : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
