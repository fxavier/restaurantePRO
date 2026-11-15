package com.xavier.bff;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keycloak's {@code restaurantpro} realm, stubbed: discovery, JWKS and the token endpoint.
 * Every access and refresh token it issues is unique, so a test can look for it anywhere.
 */
final class StubAuthorizationServer extends StubHttpServer {

    static final String REALM_PATH = "/realms/restaurantpro";
    static final String TOKEN_PATH = REALM_PATH + "/protocol/openid-connect/token";

    private final RSAKey signingKey = generate("stub-as");
    private final AtomicInteger issued = new AtomicInteger();
    private volatile String nonce;
    private volatile long accessTokenLifetimeSeconds = 300;
    private volatile boolean refusingRefresh;
    private volatile String lastAccessToken;
    private volatile String lastRefreshToken;
    private volatile String lastIdToken;

    static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    String issuer() {
        return baseUrl() + REALM_PATH;
    }

    void expectNonce(String nonce) {
        this.nonce = nonce;
    }

    void issueAccessTokensValidFor(long seconds) {
        accessTokenLifetimeSeconds = seconds;
    }

    void refuseRefresh() {
        refusingRefresh = true;
    }

    String lastAccessToken() {
        return lastAccessToken;
    }

    String lastRefreshToken() {
        return lastRefreshToken;
    }

    String lastIdToken() {
        return lastIdToken;
    }

    List<RecordedRequest> tokenRequests() {
        return received().stream().filter(r -> r.uri().getPath().equals(TOKEN_PATH)).toList();
    }

    void reset() {
        accessTokenLifetimeSeconds = 300;
        refusingRefresh = false;
        nonce = null;
        clearReceived();
    }

    @Override
    protected Response respond(RecordedRequest request) {
        String path = request.uri().getPath();
        return switch (path) {
            case REALM_PATH + "/.well-known/openid-configuration" -> json(200, discovery());
            case REALM_PATH + "/protocol/openid-connect/certs" -> json(200, new JWKSet(signingKey.toPublicJWK()).toString());
            case TOKEN_PATH -> token(request.form());
            default -> json(404, "{}");
        };
    }

    private String discovery() {
        String base = issuer() + "/protocol/openid-connect";
        return """
                {"issuer":"%s","authorization_endpoint":"%s/auth","token_endpoint":"%s/token",
                 "jwks_uri":"%s/certs","end_session_endpoint":"%s/logout",
                 "response_types_supported":["code"],"subject_types_supported":["public"],
                 "id_token_signing_alg_values_supported":["RS256"],
                 "token_endpoint_auth_methods_supported":["private_key_jwt"],
                 "code_challenge_methods_supported":["S256"]}
                """.formatted(issuer(), base, base, base, base);
    }

    private Response token(Map<String, String> form) {
        if ("refresh_token".equals(form.get("grant_type")) && refusingRefresh) {
            return json(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Token is not active\"}");
        }
        int n = issued.incrementAndGet();
        lastAccessToken = "stub-access-token-" + n;
        lastRefreshToken = "stub-refresh-token-" + n;
        lastIdToken = idToken();
        return json(200, """
                {"access_token":"%s","token_type":"Bearer","expires_in":%d,"refresh_token":"%s",
                 "refresh_expires_in":1800,"scope":"openid","id_token":"%s"}
                """.formatted(lastAccessToken, accessTokenLifetimeSeconds, lastRefreshToken, lastIdToken));
    }

    private String idToken() {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer())
                .subject("user-1")
                .audience("backoffice-bff")
                .claim("azp", "backoffice-bff")
                .claim("nonce", nonce)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Response json(int status, String body) {
        return new Response(status, Map.of("Content-Type", "application/json"), body);
    }
}
