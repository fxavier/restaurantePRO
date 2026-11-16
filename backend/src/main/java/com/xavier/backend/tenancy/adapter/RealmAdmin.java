package com.xavier.backend.tenancy.adapter;

import com.xavier.backend.tenancy.exception.IdentityProviderUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The admin REST API of one realm, as one service account (D181, D182). The account authenticates with
 * {@code private_key_jwt} (RFC 7523: an ES256 assertion signed with the key of {@link KeycloakAdminClient#keyFile()}),
 * so no client secret exists; its access token is reused until 30 s before it expires. Anything that is not an answer
 * of the realm — not configured, unreachable, a 5xx, a refused token (401) or a refused call (403, a provisioner set up
 * with too few rights) — is 503 {@code IDP_UNAVAILABLE}: nothing is assumed to have changed. Other statuses (201, 204,
 * 404, 409…) are the caller's to read. Never inside a transaction (D48); never logs a token, an assertion or a key.
 */
class RealmAdmin {

    private static final Logger log = LoggerFactory.getLogger(RealmAdmin.class);
    private static final Duration ASSERTION_LIFETIME = Duration.ofSeconds(60);
    private static final Duration REFRESH_BEFORE = Duration.ofSeconds(30);
    private static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient http;
    private final KeycloakAdminClient client;
    private final Clock clock;
    private volatile AccessToken token;

    /** One answer of the realm. */
    record Reply(int status, String location, String body) {

        /** The id at the end of a 201's {@code Location}. */
        String createdId() {
            return location.substring(location.lastIndexOf('/') + 1);
        }

        <T> T json(Class<T> type) {
            return JSON.readValue(body, type);
        }
    }

    private record AccessToken(String value, Instant expiresAt) {
    }

    RealmAdmin(RestClient.Builder http, KeycloakAdminClient client, Clock clock) {
        this.http = http.clone().build();
        this.client = client;
        this.clock = clock;
    }

    /** One admin call ({@code path} under {@code /admin/realms/<realm>}); {@code body} is serialised as JSON. */
    Reply send(HttpMethod method, String path, Object body) {
        String bearer = accessToken();
        Reply reply = exchange(method, URI.create(client.adminBase() + path), bearer, body);
        if (reply.status() == 401 || reply.status() == 403) {
            log.error("Keycloak refused {} {} to {} in realm {} ({}): check its admin rights", method, path,
                    client.clientId(), client.realm(), reply.status());
            throw new IdentityProviderUnavailableException();
        }
        return reply;
    }

    /** A GET that must answer 200, as JSON. */
    <T> T read(String path, Class<T> type) {
        Reply reply = send(HttpMethod.GET, path, null);
        if (reply.status() != 200) {
            throw new IllegalStateException("Keycloak answered " + reply.status() + " to GET " + path);
        }
        return reply.json(type);
    }

    /** The service account's access token (client credentials with a signed assertion). */
    String accessToken() {
        AccessToken current = token;
        if (current != null && clock.instant().isBefore(current.expiresAt().minus(REFRESH_BEFORE))) {
            return current.value();
        }
        synchronized (this) {
            current = token;
            if (current == null || !clock.instant().isBefore(current.expiresAt().minus(REFRESH_BEFORE))) {
                current = requestToken();
                token = current;
            }
            return current.value();
        }
    }

    private AccessToken requestToken() {
        if (!client.configured()) {
            throw new IdentityProviderUnavailableException();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", client.clientId());
        form.add("client_assertion_type", ASSERTION_TYPE);
        form.add("client_assertion", assertion());
        Instant asked = clock.instant();
        Reply reply = exchange(HttpMethod.POST, URI.create(client.issuer() + "/protocol/openid-connect/token"), null,
                form);
        if (reply.status() != 200) {
            log.warn("Keycloak refused a token to {} in realm {} ({})", client.clientId(), client.realm(),
                    reply.status());
            throw new IdentityProviderUnavailableException();
        }
        Map<?, ?> answer = reply.json(Map.class);
        long lifetime = ((Number) answer.get("expires_in")).longValue();
        return new AccessToken((String) answer.get("access_token"), asked.plusSeconds(lifetime));
    }

    private Reply exchange(HttpMethod method, URI uri, String bearer, Object body) {
        try {
            RestClient.RequestBodySpec request = http.method(method).uri(uri);
            if (bearer != null) {
                request.header("Authorization", "Bearer " + bearer);
            }
            if (body instanceof MultiValueMap<?, ?> form) {
                request.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form);
            } else if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(body));
            }
            Reply reply = request.exchange((req, response) -> new Reply(response.getStatusCode().value(),
                    response.getHeaders().getFirst("Location"),
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (reply.status() >= 500) {
                log.warn("Keycloak answered {} to {} {}", reply.status(), method, uri.getPath());
                throw new IdentityProviderUnavailableException();
            }
            return reply;
        } catch (ResourceAccessException e) {
            log.warn("Keycloak is unreachable at {}", uri.getHost(), e);
            throw new IdentityProviderUnavailableException();
        }
    }

    /** RFC 7523 client assertion: iss = sub = the client, aud = the realm's issuer, one minute, unique jti. */
    private String assertion() {
        Instant now = clock.instant();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", client.clientId());
        claims.put("sub", client.clientId());
        claims.put("aud", client.issuer());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(ASSERTION_LIFETIME).getEpochSecond());
        String signingInput = base64Url(JSON.writeValueAsBytes(Map.of("alg", "ES256", "typ", "JWT"))) + "."
                + base64Url(JSON.writeValueAsBytes(claims));
        try {
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(privateKey());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign the client assertion of " + client.clientId(), e);
        }
    }

    private PrivateKey privateKey() throws GeneralSecurityException {
        String pem;
        try {
            pem = Files.readString(Path.of(client.keyFile()));
        } catch (IOException e) {
            log.error("Cannot read the private key file of {} (restaurantpro.keycloak.*.key-file)", client.clientId());
            throw new IdentityProviderUnavailableException();
        }
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder()
                .decode(base64)));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
