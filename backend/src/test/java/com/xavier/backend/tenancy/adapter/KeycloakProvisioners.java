package com.xavier.backend.tenancy.adapter;

import com.xavier.backend.SharedKeycloak;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The deploy-time set-up of the two provisioners (O1 option 3), done on the shared Keycloak Testcontainer
 * ({@link SharedKeycloak}, which imports every {@code deploy/keycloak} realm) as its master administrator, once per
 * JVM: in {@code restaurantpro}, FGAP v2 on and the
 * {@code tenancy-provisioner} client with Users and Organizations permissions only (the change S72 makes to
 * {@code realm-restaurantpro.json}); each provisioner's public key registered (the private keys live in temporary
 * files only); and the tenant realm's SMTP pointed at a Mailpit, so the invitation email is observable.
 */
final class KeycloakProvisioners {

    static final String TENANT_REALM = "restaurantpro";
    static final String OPS_REALM = "restaurantpro-ops";
    static final String DEVICE_REALM = "restaurantpro-devices";
    static final String MAILPIT_IMAGE = "axllent/mailpit:v1.27";
    static final List<String> REALM_FILES = List.of("realm-restaurantpro.json", "realm-restaurantpro-ops.json",
            "realm-restaurantpro-devices.json");

    private static volatile KeycloakProvisioners instance;

    final String baseUrl;
    final KeycloakAdminClient tenantProvisioner;
    final KeycloakAdminClient deviceProvisioner;
    final String mailpitUrl;
    private final KeycloakContainer keycloak;

    private KeycloakProvisioners(KeycloakContainer keycloak, String mailpitUrl, Path tenantKey, Path deviceKey) {
        this.keycloak = keycloak;
        this.baseUrl = keycloak.getAuthServerUrl().replaceAll("/+$", "");
        this.mailpitUrl = mailpitUrl;
        this.tenantProvisioner = new KeycloakAdminClient(baseUrl, TENANT_REALM, "tenancy-provisioner",
                tenantKey.toString());
        this.deviceProvisioner = new KeycloakAdminClient(baseUrl, DEVICE_REALM, "device-provisioner",
                deviceKey.toString());
    }

    static synchronized KeycloakProvisioners instance() throws Exception {
        if (instance == null) {
            instance = setUp();
        }
        return instance;
    }

    private static KeycloakProvisioners setUp() throws Exception {
        KeycloakContainer keycloak = SharedKeycloak.instance().container();
        GenericContainer<?> mailpit = new GenericContainer<>(MAILPIT_IMAGE).withExposedPorts(1025, 8025)
                .waitingFor(Wait.forHttp("/livez").forPort(8025));
        mailpit.start();
        String mailpitIp = mailpit.getContainerInfo().getNetworkSettings().getNetworks().get("bridge").getIpAddress();
        KeycloakProvisioners fixture = new KeycloakProvisioners(keycloak,
                "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025), newKey("tenancy"),
                newKey("device"));
        fixture.configure(mailpitIp);
        return fixture;
    }

    private void configure(String mailpitIp) throws Exception {
        for (String file : REALM_FILES) { // SharedKeycloak imports them; a missing one is imported here
            String realm = Files.readString(RealmFiles.directory().resolve(file));
            String name = (String) tools.jackson.databind.json.JsonMapper.builder().build().readValue(realm, Map.class)
                    .get("realm");
            if (status(HttpMethod.GET, "/admin/realms/" + name, master(), null).value() == 404) {
                expect(status(HttpMethod.POST, "/admin/realms", master(), realm), 201);
            }
        }
        register(DEVICE_REALM, "device-provisioner", Path.of(deviceProvisioner.keyFile()));

        Map<String, Object> tenantRealm = admin().get().uri(baseUrl + "/admin/realms/" + TENANT_REALM)
                .retrieve().body(Map.class);
        tenantRealm.put("adminPermissionsEnabled", true);
        tenantRealm.put("smtpServer", Map.of("host", mailpitIp, "port", "1025", "from", "noreply@restaurantpro.test",
                "auth", "false", "ssl", "false", "starttls", "false"));
        expect(status(HttpMethod.PUT, "/admin/realms/" + TENANT_REALM, master(), tenantRealm), 204);
        if (clientUuid(TENANT_REALM, "tenancy-provisioner") == null) {
            expect(status(HttpMethod.POST, "/admin/realms/" + TENANT_REALM + "/clients", master(), Map.of(
                    "clientId", "tenancy-provisioner", "enabled", true, "publicClient", false,
                    "clientAuthenticatorType", "client-jwt", "serviceAccountsEnabled", true,
                    "standardFlowEnabled", false, "directAccessGrantsEnabled", false,
                    "attributes", Map.of("token.endpoint.auth.signing.alg", "ES256"))), 201);
        }
        register(TENANT_REALM, "tenancy-provisioner", Path.of(tenantProvisioner.keyFile()));
        grantUsersAndOrganizations();
    }

    /** FGAP v2: the tenancy-provisioner may manage users and organizations of the tenant realm, and nothing else. */
    private void grantUsersAndOrganizations() {
        String provisioner = clientUuid(TENANT_REALM, "tenancy-provisioner");
        Map<?, ?> serviceAccount = admin().get().uri(baseUrl + "/admin/realms/" + TENANT_REALM + "/clients/"
                + provisioner + "/service-account-user").retrieve().body(Map.class);
        String authz = "/admin/realms/" + TENANT_REALM + "/clients/" + clientUuid(TENANT_REALM, "admin-permissions")
                + "/authz/resource-server";
        String policyName = "caller-is-tenancy-provisioner";
        status(HttpMethod.POST, authz + "/policy/user", master(), Map.of("name", policyName,
                "users", List.of(serviceAccount.get("id")), "logic", "POSITIVE"));
        List<Map<String, Object>> policies = admin().get().uri(baseUrl + authz + "/policy?name=" + policyName)
                .retrieve().body(List.class);
        Object policy = policies.getFirst().get("id");
        for (String type : List.of("Users", "Organizations")) {
            status(HttpMethod.POST, authz + "/permission/scope", master(), Map.of(
                    "name", "tenancy-provisioner-" + type.toLowerCase(), "resourceType", type,
                    "scopes", List.of("view", "manage"), "policies", List.of(policy)));
        }
    }

    /** Registers the public half of the key in {@code keyFile}'s sibling {@code .jwk} on the client. */
    private void register(String realm, String clientId, Path keyFile) {
        String uuid = clientUuid(realm, clientId);
        String jwk = Files.exists(Path.of(keyFile + ".jwk")) ? read(Path.of(keyFile + ".jwk")) : null;
        expect(status(HttpMethod.PUT, "/admin/realms/" + realm + "/clients/" + uuid, master(), Map.of(
                "clientAuthenticatorType", "client-jwt",
                "attributes", Map.of("use.jwks.string", "true", "jwks.string", "{\"keys\":[" + jwk + "]}",
                        "token.endpoint.auth.signing.alg", "ES256"))), 204);
    }

    String clientUuid(String realm, String clientId) {
        List<Map<String, Object>> clients = admin().get().uri(baseUrl + "/admin/realms/" + realm + "/clients?clientId="
                + clientId).retrieve().body(List.class);
        return clients.isEmpty() ? null : (String) clients.getFirst().get("id");
    }

    Map<String, Object> client(String realm, String clientId) {
        return admin().get().uri(baseUrl + "/admin/realms/" + realm + "/clients/" + clientUuid(realm, clientId))
                .retrieve().body(Map.class);
    }

    /** The status of one admin call made with {@code token} (no exception on a 4xx). */
    HttpStatusCode status(HttpMethod method, String path, String token, Object body) {
        RestClient.RequestBodySpec request = RestClient.create().method(method).uri(baseUrl + path)
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange((req, response) -> response.getStatusCode());
    }

    String master() {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", keycloak.getAdminUsername());
        form.add("password", keycloak.getAdminPassword());
        Map<?, ?> token = RestClient.create().post().uri(baseUrl + "/realms/master/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
        return (String) token.get("access_token");
    }

    private RestClient admin() {
        return RestClient.builder().defaultHeader("Authorization", "Bearer " + master()).build();
    }

    /** The addresses Mailpit received a message for. */
    List<String> mailRecipients() {
        Map<?, ?> messages = RestClient.create().get().uri(mailpitUrl + "/api/v1/messages").retrieve()
                .body(Map.class);
        return ((List<Map<String, Object>>) messages.get("messages")).stream()
                .flatMap(message -> ((List<Map<String, Object>>) message.get("To")).stream())
                .map(to -> (String) to.get("Address")).toList();
    }

    /** A fresh EC P-256 key: the private key as PEM PKCS#8 in a 0600 temp file, its public JWK next to it. */
    static Path newKey(String name) throws Exception {
        KeyPair pair = ecKeyPair();
        Path file = Files.createTempFile("rp-" + name + "-", ".pem",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        file.toFile().deleteOnExit();
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        Path jwk = Path.of(file + ".jwk");
        Files.writeString(jwk, publicJwk((ECPublicKey) pair.getPublic(), name));
        jwk.toFile().deleteOnExit();
        return file;
    }

    static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static String publicJwk(ECPublicKey key, String kid) {
        return "{\"kty\":\"EC\",\"crv\":\"P-256\",\"use\":\"sig\",\"alg\":\"ES256\",\"kid\":\"" + kid + "\",\"x\":\""
                + coordinate(key.getW().getAffineX()) + "\",\"y\":\"" + coordinate(key.getW().getAffineY()) + "\"}";
    }

    private static String coordinate(java.math.BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] fixed = new byte[32];
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, fixed, 32 - length, length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void expect(HttpStatusCode status, int expected) {
        if (status.value() != expected) {
            throw new IllegalStateException("Keycloak set-up answered " + status + ", expected " + expected);
        }
    }

    /** Finds {@code deploy/keycloak} above the working directory, as {@link SharedKeycloak} does. */
    static final class RealmFiles {

        static Path directory() {
            for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
                Path candidate = dir.resolve("deploy/keycloak");
                if (Files.isRegularFile(candidate.resolve(REALM_FILES.getLast()))) {
                    return candidate;
                }
            }
            throw new IllegalStateException("deploy/keycloak not found above " + Path.of("").toAbsolutePath());
        }
    }
}
